package universidad.mensajeria.protocolo.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import universidad.mensajeria.common.codec.TramaCodec;
import universidad.mensajeria.common.interno.EstadoPool;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.common.tipos.TipoMensaje;
import universidad.mensajeria.protocolo.ConfigRed;
import universidad.mensajeria.protocolo.InterfazProtocoloComunicacion;
import universidad.mensajeria.protocolo.ReceptorDeTramas;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;

/** Transporte TCP: el pool acotado conserva hilos y manejadores entre sesiones. */
public final class ProtocoloComunicacion implements InterfazProtocoloComunicacion {

    private static final Logger LOG = LoggerFactory.getLogger(ProtocoloComunicacion.class);

    private final ConfigRed config;
    private final FabricaPoolDeTrabajadores fabricaPool;
    private final ConcurrentHashMap<String, SesionTcp> vivas = new ConcurrentHashMap<>();

    private volatile ServerSocket serverSocket;
    private volatile ServerSocket tlsSocket;
    private volatile PoolDeTrabajadores pool;
    private volatile boolean activo;
    private volatile ReceptorDeTramas receptor = new ReceptorDeTramas() {
        @Override
        public void alConectar(IdSesion sesion) {
        }

        @Override
        public void alRecibir(IdSesion sesion, Mensaje mensaje) {
        }

        @Override
        public void alDesconectar(IdSesion sesion, String motivo) {
        }
    };

    public ProtocoloComunicacion(ConfigRed config) {
        this(config, new FabricaPoolDeTrabajadores());
    }

    ProtocoloComunicacion(ConfigRed config, FabricaPoolDeTrabajadores fabricaPool) {
        this.config = config;
        this.fabricaPool = fabricaPool;
    }

    @Override
    public synchronized void iniciar() {
        if (activo) {
            LOG.warn("El servidor ya esta en ejecucion");
            return;
        }
        try {
            serverSocket = new ServerSocket(config.puerto());
            serverSocket.setReuseAddress(true);
            pool = fabricaPool.crear(config.poolMax(), vivas, () -> receptor,
                    config.timeoutSegundos(), config.poolEsperaSegundos(),
                    config.tramasPorSegundo());
            activo = true;
            Thread hiloAceptador = new Thread(() -> esperarClientes(serverSocket), "hilo-aceptador");
            hiloAceptador.setDaemon(true);
            hiloAceptador.start();
            if (config.tlsHabilitado()) {
                tlsSocket = servidorTls(config);
                Thread hiloTls = new Thread(() -> esperarClientes(tlsSocket), "hilo-aceptador-tls");
                hiloTls.setDaemon(true);
                hiloTls.start();
                LOG.info("Servidor TLS escuchando en el puerto {}", config.tlsPuerto());
            }
            LOG.info("Servidor TCP escuchando en el puerto {} (trabajadores={})",
                    puerto(), config.poolMax());
        } catch (IOException | RuntimeException e) {
            activo = false;
            if (pool != null) pool.cerrar();
            pool = null;
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException ignorada) {
                    // ya cerrado
                }
            }
            throw new IllegalStateException(
                    "No se pudo abrir el puerto " + config.puerto() + ": " + e.getMessage(), e);
        }
    }

    /**
     * FASE 11.2: TLS opcional con doble puerto (el plano sigue para peers sin
     * TLS como Python hasta que migre). El framing no cambia: SSLSocket es un
     * Socket y SesionTcp lo consume igual.
     */
    private static ServerSocket servidorTls(ConfigRed config) throws IOException {
        try {
            char[] clave = config.tlsClave() == null ? new char[0]
                    : config.tlsClave().toCharArray();
            java.security.KeyStore almacen =
                    java.security.KeyStore.getInstance("PKCS12");
            try (java.io.InputStream in =
                         java.nio.file.Files.newInputStream(java.nio.file.Path.of(config.tlsAlmacen()))) {
                almacen.load(in, clave);
            }
            javax.net.ssl.KeyManagerFactory fabricaClaves =
                    javax.net.ssl.KeyManagerFactory.getInstance(
                            javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            fabricaClaves.init(almacen, clave);
            javax.net.ssl.SSLContext contexto = javax.net.ssl.SSLContext.getInstance("TLS");
            contexto.init(fabricaClaves.getKeyManagers(), null, null);
            javax.net.ssl.SSLServerSocket servidor =
                    (javax.net.ssl.SSLServerSocket) contexto.getServerSocketFactory()
                            .createServerSocket(config.tlsPuerto());
            servidor.setReuseAddress(true);
            return servidor;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("no se pudo abrir el TLS en " + config.tlsPuerto()
                    + ": " + e.getMessage(), e);
        }
    }

    private void esperarClientes(ServerSocket escucha) {
        while (activo) {
            try {
                Socket socket = escucha.accept();
                socket.setTcpNoDelay(true);
                PoolDeTrabajadores poolActivo = pool;
                ManejadorConexion trabajador;
                if (poolActivo == null) {
                    trabajador = null;
                } else if (config.poolEsperaSegundos() > 0) {
                    trabajador = poolActivo.adquirir(config.poolEsperaSegundos(),
                            java.util.concurrent.TimeUnit.SECONDS);
                } else {
                    trabajador = poolActivo.adquirir();
                }
                if (trabajador == null) {
                    rechazarPorSaturacion(socket);
                    continue;
                }
                try {
                    trabajador.preparar(socket);
                    SesionTcp sesion = trabajador.sesion();
                    vivas.put(sesion.id().id(), sesion);
                    receptor.alConectar(sesion.id());
                    trabajador.iniciarAtencion();
                } catch (IOException | RuntimeException fallo) {
                    SesionTcp sesion = trabajador.sesion();
                    if (sesion != null) {
                        vivas.remove(sesion.id().id(), sesion);
                        sesion.cerrar();
                    } else {
                        cerrar(socket);
                    }
                    trabajador.cancelar();
                    poolActivo.liberar(trabajador);
                    LOG.warn("No se pudo asignar trabajador: {}", fallo.getMessage());
                }
            } catch (IOException e) {
                if (activo) LOG.error("Error aceptando conexiones: {}", e.getMessage());
            }
        }
    }

    private void rechazarPorSaturacion(Socket socket) {
        String ip = socket.getInetAddress() == null
                ? "desconocida" : socket.getInetAddress().getHostAddress();
        LOG.warn("Pool saturado (max={}): se rechaza la conexion de {}", config.poolMax(), ip);
        try {
            receptor.alRechazoPorSaturacion(ip, config.poolMax());
        } catch (RuntimeException e) {
            LOG.debug("Receptor no registro el rechazo: {}", e.getMessage());
        }
        try (socket) {
            TramaCodec.escribir(socket.getOutputStream(), Mensaje.builder()
                    .tipo(TipoMensaje.ERROR)
                    .fechaHora(LocalDateTime.now().toString())
                    .exito(false)
                    .mensajeError("servidor lleno: intente conectarse mas tarde")
                    .build());
        } catch (IOException e) {
            LOG.debug("No se pudo enviar el aviso de saturacion a {}: {}", ip, e.getMessage());
        }
    }

    private static void cerrar(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignorada) {
            // ya cerrado
        }
    }

    @Override
    public synchronized void detener() {
        activo = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignorada) {
                // ya cerrado
            }
        }
        if (tlsSocket != null) {
            try {
                tlsSocket.close();
            } catch (IOException ignorada) {
                // ya cerrado
            }
            tlsSocket = null;
        }
        vivas.values().forEach(SesionTcp::cerrar);
        PoolDeTrabajadores poolActivo = pool;
        if (poolActivo != null) poolActivo.cerrar();
        vivas.clear();
        pool = null;
        LOG.info("Servidor TCP detenido (puerto {})", config.puerto());
    }

    @Override
    public boolean estaActivo() {
        return activo;
    }

    @Override
    public int puerto() {
        if (serverSocket != null && serverSocket.isBound()) return serverSocket.getLocalPort();
        return config.puerto();
    }

    /** Puerto TLS efectivo (resuelve el 0 efimero); -1 si TLS apagado. */
    public int puertoTls() {
        if (tlsSocket != null && tlsSocket.isBound()) return tlsSocket.getLocalPort();
        return config.tlsHabilitado() ? config.tlsPuerto() : -1;
    }

    @Override
    public EstadoPool estadoPool() {
        PoolDeTrabajadores poolActivo = pool;
        return poolActivo == null
                ? new EstadoPool(0, 0, Math.max(1, config.poolMax()))
                : poolActivo.estado();
    }

    @Override
    public void enviar(IdSesion sesion, Mensaje mensaje) throws IOException {
        SesionTcp tcp = vivas.get(sesion.id());
        if (tcp == null) throw new IllegalArgumentException("Sesion desconocida: " + sesion.id());
        tcp.enviar(mensaje);
    }

    @Override
    public IdSesion autenticar(IdSesion sesion, String codigo) {
        SesionTcp tcp = vivas.get(sesion.id());
        if (tcp == null) throw new IllegalArgumentException("Sesion desconocida: " + sesion.id());
        tcp.autenticar(codigo);
        return tcp.id();
    }

    @Override
    public void cerrarSesion(IdSesion sesion, String motivo) {
        SesionTcp tcp = vivas.remove(sesion.id());
        if (tcp == null) return;
        try {
            tcp.enviar(Mensaje.builder()
                    .tipo(TipoMensaje.CLOSE_NOTICE)
                    .fechaHora(LocalDateTime.now().toString())
                    .exito(false)
                    .mensajeError(motivo)
                    .build());
        } catch (IOException ignorada) {
            // El peer ya no lee.
        } finally {
            tcp.cerrar();
        }
        receptor.alDesconectar(tcp.id(), motivo);
    }

    @Override
    public void registrarReceptor(ReceptorDeTramas receptor) {
        this.receptor = receptor;
    }

    int sesionesVivas() {
        return vivas.size();
    }
}
