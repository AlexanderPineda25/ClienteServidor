package universidad.mensajeria.protocolo.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import universidad.mensajeria.common.codec.CodecException;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.common.tipos.TipoMensaje;
import universidad.mensajeria.protocolo.ReceptorDeTramas;

import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Trabajador TCP preinstanciado que atiende conexiones sucesivas. */
final class ManejadorConexion extends Thread {

    private static final Logger LOG = LoggerFactory.getLogger(ManejadorConexion.class);

    private final ConcurrentHashMap<String, SesionTcp> vivas;
    private final Supplier<ReceptorDeTramas> receptor;
    private final long timeoutMs;
    private final PoolDeTrabajadores pool;
    private final ArrayBlockingQueue<SesionTcp> asignaciones = new ArrayBlockingQueue<>(1);
    private final AtomicBoolean ocupado = new AtomicBoolean();
    private volatile SesionTcp sesion;
    private volatile boolean detenido;
    private final int tramasPorSegundo;
    /** Token bucket por conexion (FASE 11.2): rafaga inicial = 2x tasa. */
    private double fichas;
    private long ultimoRecargoNs;

    ManejadorConexion(String nombre, ConcurrentHashMap<String, SesionTcp> vivas,
                      Supplier<ReceptorDeTramas> receptor, int timeoutSegundos,
                      int tramasPorSegundo, PoolDeTrabajadores pool) {
        super(nombre);
        this.vivas = vivas;
        this.receptor = receptor;
        this.timeoutMs = Math.max(1, timeoutSegundos) * 1000L;
        this.tramasPorSegundo = Math.max(0, tramasPorSegundo);
        this.pool = pool;
        setDaemon(true);
    }

    void preparar(Socket socket) throws IOException {
        SesionTcp nueva = new SesionTcp(socket);
        nueva.fijarTimeoutLectura((int) Math.min(Integer.MAX_VALUE, timeoutMs));
        sesion = nueva;
        ocupado.set(true);
        fichas = tramasPorSegundo <= 0 ? Double.MAX_VALUE : 2.0 * tramasPorSegundo;
        ultimoRecargoNs = System.nanoTime();
    }

    /** true si la trama cabe en la tasa; 0 configurado = sin limite. */
    private boolean consumirFicha() {
        if (tramasPorSegundo <= 0) {
            return true;
        }
        long ahora = System.nanoTime();
        double transcurrido = (ahora - ultimoRecargoNs) / 1_000_000_000.0;
        ultimoRecargoNs = ahora;
        fichas = Math.min(2.0 * tramasPorSegundo, fichas + transcurrido * tramasPorSegundo);
        if (fichas < 1.0) {
            return false;
        }
        fichas -= 1.0;
        return true;
    }

    SesionTcp sesion() {
        return sesion;
    }

    void iniciarAtencion() {
        if (sesion == null || !asignaciones.offer(sesion)) {
            throw new IllegalStateException("El trabajador no esta disponible");
        }
    }

    void cancelar() {
        SesionTcp actual = sesion;
        if (actual != null) {
            asignaciones.remove(actual);
            actual.cerrar();
        }
        sesion = null;
        ocupado.set(false);
    }

    boolean reiniciable() {
        return !detenido && isAlive() && !ocupado.get() && sesion == null;
    }

    void detener() {
        detenido = true;
        SesionTcp actual = sesion;
        if (actual != null) actual.cerrar();
        interrupt();
    }

    @Override
    public void run() {
        while (!detenido) {
            SesionTcp actual;
            try {
                actual = asignaciones.take();
            } catch (InterruptedException e) {
                if (detenido) break;
                continue;
            }
            atender(actual);
        }
    }

    private void atender(SesionTcp actual) {
        LOG.info("Handler iniciado para {}", actual.id().direccionIp());
        long ultimaActividad = System.currentTimeMillis();
        String motivoCierre = null;
        try {
            while (!detenido && actual.activa()) {
                Mensaje entrada;
                try {
                    entrada = actual.leer();
                    ultimaActividad = System.currentTimeMillis();
                } catch (SocketTimeoutException inactivo) {
                    if (System.currentTimeMillis() - ultimaActividad >= timeoutMs) {
                        LOG.info("Sesion inactiva ({} ms): {}", timeoutMs, actual);
                        motivoCierre = "cierre por inactividad";
                        avisarCierre(actual, motivoCierre);
                        break;
                    }
                    continue;
                } catch (EOFException | SocketException desconectado) {
                    LOG.info("Cliente desconectado: {}", actual);
                    motivoCierre = "corte de red";
                    break;
                } catch (CodecException tramaMala) {
                    LOG.warn("Trama invalida de {} ({}): se cierra la conexion",
                            actual.id().direccionIp(), tramaMala.getMessage());
                    motivoCierre = "trama invalida: " + tramaMala.getMessage();
                    avisarError(actual, motivoCierre);
                    break;
                }
                if (!consumirFicha()) {
                    LOG.warn("Limite de frecuencia excedido de {} (max {}/s): trama descartada",
                            actual.id().direccionIp(), tramasPorSegundo);
                    avisarError(actual, "limite de frecuencia excedido (max "
                            + tramasPorSegundo + "/s)");
                    continue;
                }
                try {
                    receptor.get().alRecibir(actual.id(), entrada);
                } catch (Exception e) {
                    LOG.error("Error en receptor para {}: {}", actual, e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.info("Conexion finalizada con {}: {}", actual, e.getMessage());
            if (motivoCierre == null) motivoCierre = "corte de red";
        } finally {
            if (vivas.remove(actual.id().id(), actual)) {
                actual.cerrar();
                try {
                    receptor.get().alDesconectar(actual.id(),
                            motivoCierre != null ? motivoCierre : "fin de conexion");
                } catch (RuntimeException e) {
                    LOG.warn("No se pudo notificar desconexion: {}", e.getMessage());
                }
            } else {
                actual.cerrar();
            }
            sesion = null;
            ocupado.set(false);
            LOG.info("Sesion finalizada: {}", actual);
            if (!detenido) pool.liberar(this);
        }
    }

    private static void avisarCierre(SesionTcp sesion, String motivo) {
        try {
            sesion.enviar(Mensaje.builder()
                    .tipo(TipoMensaje.CLOSE_NOTICE)
                    .fechaHora(LocalDateTime.now().toString())
                    .exito(false)
                    .mensajeError(motivo)
                    .build());
        } catch (IOException ignorada) {
            // El peer ya no lee.
        }
    }

    private static void avisarError(SesionTcp sesion, String motivo) {
        try {
            sesion.enviar(Mensaje.builder()
                    .tipo(TipoMensaje.ERROR)
                    .fechaHora(LocalDateTime.now().toString())
                    .mensajeError(motivo)
                    .build());
        } catch (IOException ignorada) {
            // Sin conexion no hay a quien avisar.
        }
    }
}
