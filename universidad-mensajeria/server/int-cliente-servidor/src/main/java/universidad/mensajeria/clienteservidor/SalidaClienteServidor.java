package universidad.mensajeria.clienteservidor;

import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.common.tipos.TipoMensaje;

/**
 * Puertos de salida divididos por rol — ISP (PLAN2 §2.5 Regla 2).
 * Los implementa la Fachada (que ya tiene flecha a este int-*).
 */
public interface SalidaClienteServidor {

    /** Autenticacion y cierre (el alta por red esta eliminada: solo CSV). */
    interface SalidaAutenticacion {
        void autenticar(IdSesion sesion, String codigo, String contrasena, String idSolicitud);

        void cerrar(IdSesion sesion, String idSolicitud);

        /** KICK propio: cierra las DEMAS sesiones del codigo (auto-kick, req. #10). */
        void expulsarOtrasSesiones(IdSesion sesion, String codigo, String idSolicitud);
    }

    /** Mensajeria: la Fachada valida, procesa, persiste, responde y entrega. */
    interface SalidaMensajeria {
        void recibirTexto(IdSesion sesion, String destinatario, String contenido,
                          String idSolicitud, String ip);

        void recibirImagen(IdSesion sesion, String destinatario, String nombreArchivo,
                           String mime, String contenidoBase64, String idSolicitud, String ip);

        void recibirArchivo(IdSesion sesion, String destinatario, String nombreArchivo,
                            String mime, String contenidoBase64, String idSolicitud, String ip);

        /** Fragmentos de archivo 50 MB (PROTOCOLO.md §3/§5): correlacion por idSolicitud. */
        void iniciarArchivo(IdSesion sesion, String destinatario, String nombreArchivo,
                            String mime, long tamanoTotal, int totalPartes, String idSolicitud, String ip);

        void parteArchivo(IdSesion sesion, String idTransferencia, int indiceParte,
                           String contenidoBase64, String idSolicitud, String ip);

        void finalizarArchivo(IdSesion sesion, String idTransferencia, String hashSha256,
                               String idSolicitud, String ip);

        void difundir(IdSesion sesion, String contenido, String idSolicitud, String ip);

        void historial(IdSesion sesion, String otro, int pagina, String idSolicitud);

        void descargar(IdSesion sesion, String archivoId, String idSolicitud);

        /** Reenvía MENSAJE_LEIDO del lector al autor (default no-op por compat). */
        default void notificarLectura(IdSesion sesion, String destinatario, String idOriginal,
                                      String idSolicitud) {
        }
    }

    /** Consultas de presencia y directorio. */
    interface SalidaConsultas {
        void listarConectados(IdSesion sesion, String idSolicitud);

        void listarUsuarios(IdSesion sesion, String idSolicitud);
    }

    /** Respuestas y errores correlacionados. */
    interface SalidaRespuestas {
        void responder(IdSesion sesion, Mensaje respuesta);

        void error(IdSesion sesion, String idSolicitud, TipoMensaje tipo, String motivo);
    }

    /** Contrato completo que implementa la Fachada. */
    interface FachadaSalida extends SalidaClienteServidor, SalidaAutenticacion, SalidaMensajeria,
            SalidaConsultas, SalidaRespuestas {
    }
}
