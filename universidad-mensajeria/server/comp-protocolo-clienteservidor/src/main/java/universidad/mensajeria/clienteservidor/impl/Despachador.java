package universidad.mensajeria.clienteservidor.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import universidad.mensajeria.clienteservidor.InterfazClienteServidor;
import universidad.mensajeria.clienteservidor.SalidaClienteServidor;
import universidad.mensajeria.clienteservidor.SalidaClienteServidor.SalidaRespuestas;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.common.tipos.TipoMensaje;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Despacho sin switch (PLAN2 §2.5): tipo → CommandHandler. Un tipo sin
 * handler responde ERROR por la salida; nunca tumba la sesion.
 */
public final class Despachador implements InterfazClienteServidor {

    private static final Logger LOG = LoggerFactory.getLogger(Despachador.class);

    private final Map<String, CommandHandler> comandos;
    private volatile SalidaRespuestas salida = new SalidaRespuestas() {
        @Override
        public void responder(IdSesion sesion, Mensaje respuesta) {
        }

        @Override
        public void error(IdSesion sesion, String idSolicitud, TipoMensaje tipo, String motivo) {
        }
    };

    public Despachador(List<CommandHandler> handlers) {
        Map<String, CommandHandler> mapa = new java.util.HashMap<>();
        for (CommandHandler h : handlers) {
            try {
                TipoMensaje.valueOf(h.tipo());
            } catch (IllegalArgumentException desconocido) {
                throw new IllegalStateException(
                        "CommandHandler '" + h.tipo() + "' no pertenece al catalogo TipoMensaje");
            }
            if (mapa.putIfAbsent(h.tipo(), h) != null) {
                throw new IllegalStateException("CommandHandler duplicado: " + h.tipo());
            }
        }
        if (mapa.isEmpty()) {
            throw new IllegalStateException("Sin CommandHandler registrados: nada que despachar");
        }
        this.comandos = Map.copyOf(mapa);
    }

    @Override
    public void procesar(Mensaje mensaje, IdSesion sesion) {
        CommandHandler comando = comandos.get(mensaje.tipo().name());
        if (comando == null) {
            LOG.warn("Tipo no soportado: {}", mensaje.tipo());
            salida().error(sesion, mensaje.id(), TipoMensaje.ERROR,
                    "tipo no soportado: " + mensaje.tipo());
            return;
        }
        try {
            comando.handle(mensaje, sesion);
        } catch (Exception e) {
            LOG.error("Error procesando {} de {}: {}", mensaje.tipo(), sesion, e.getMessage());
            salida().error(sesion, mensaje.id(), TipoMensaje.ERROR,
                    "error interno procesando " + mensaje.tipo());
        }
    }

    @Override
    public void registrarSalida(SalidaClienteServidor salida) {
        if (salida instanceof SalidaRespuestas respuestas) {
            this.salida = respuestas;
        } else {
            throw new IllegalArgumentException("La salida debe implementar SalidaRespuestas");
        }
    }

    private SalidaRespuestas salida() {
        return salida;
    }

    /** Tipos despachables (trazabilidad/tests). */
    public Map<String, CommandHandler> comandos() {
        return comandos;
    }

    /** Catálogo que este despachador cubre (para el test de fidelidad). */
    public static List<String> tiposSoportados() {
        return List.of("LOGIN", "LOGOUT", "LISTAR_CONECTADOS", "LISTAR_USUARIOS",
                "MENSAJE_TEXTO", "MENSAJE_IMAGEN", "MENSAJE_ARCHIVO", "ARCHIVO_INICIO",
                "ARCHIVO_PARTE", "ARCHIVO_FIN", "BROADCAST",
                "HISTORIAL_REQ", "DESCARGAR_ARCHIVO", "KICK", "MENSAJE_LEIDO");
    }

    /** Mapa tipo → clase handler (para el test de fidelidad). */
    public static Map<String, String> mapaTipos() {
        Map<String, String> mapa = new java.util.LinkedHashMap<>();
        mapa.put("LOGIN", "LoginCommand");
        mapa.put("LOGOUT", "LogoutCommand");
        mapa.put("LISTAR_CONECTADOS", "ListarCommand");
        mapa.put("LISTAR_USUARIOS", "ListarUsuariosCommand");
        mapa.put("MENSAJE_TEXTO", "TextoCommand");
        mapa.put("MENSAJE_IMAGEN", "ImagenCommand");
        mapa.put("MENSAJE_ARCHIVO", "ArchivoCommand");
        mapa.put("ARCHIVO_INICIO", "ArchivoInicioCommand");
        mapa.put("ARCHIVO_PARTE", "ArchivoParteCommand");
        mapa.put("ARCHIVO_FIN", "ArchivoFinCommand");
        mapa.put("BROADCAST", "BroadcastCommand");
        mapa.put("HISTORIAL_REQ", "HistorialCommand");
        mapa.put("DESCARGAR_ARCHIVO", "DescargaCommand");
        mapa.put("KICK", "CierreCommand");
        mapa.put("MENSAJE_LEIDO", "LeidoCommand");
        return Map.copyOf(mapa);
    }
}
