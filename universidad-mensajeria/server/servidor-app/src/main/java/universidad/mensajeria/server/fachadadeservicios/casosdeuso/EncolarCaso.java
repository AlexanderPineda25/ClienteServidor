package universidad.mensajeria.server.fachadadeservicios.casosdeuso;

import universidad.mensajeria.colas.InterfazGestorMensajes;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.common.tipos.TipoMensaje;

import java.time.LocalDateTime;

/**
 * Casos de mensajeria (PLAN2 §2.5 paso 3): SOLO encolan la trama; el
 * consumidor (Fachada) encadena Mensajes.procesarX al encolar. El ACK y la
 * entrega salen al completar el futuro, nunca aqui.
 */
public final class EncolarCaso {

    private final InterfazGestorMensajes colas;

    public EncolarCaso(InterfazGestorMensajes colas) {
        this.colas = colas;
    }

    public void texto(IdSesion sesion, String destinatario, String contenido,
                      String idSolicitud, String ip) {
        colas.encolar(Mensaje.builder()
                .tipo(TipoMensaje.MENSAJE_TEXTO)
                .id(idSolicitud)
                .fechaHora(LocalDateTime.now().toString())
                .remitente(sesion.codigo())
                .destinatario(destinatario)
                .contenido(contenido)
                .ipRemitente(ip)
                .build());
    }

    public void imagen(IdSesion sesion, String destinatario, String nombreArchivo, String mime,
                       String contenidoBase64, String idSolicitud, String ip) {
        colas.encolar(Mensaje.builder()
                .tipo(TipoMensaje.MENSAJE_IMAGEN)
                .id(idSolicitud)
                .fechaHora(LocalDateTime.now().toString())
                .remitente(sesion.codigo())
                .destinatario(destinatario)
                .nombreArchivo(nombreArchivo)
                .mime(mime)
                .contenidoImagen(contenidoBase64)
                .ipRemitente(ip)
                .build());
    }

    public void archivo(IdSesion sesion, String destinatario, String nombreArchivo, String mime,
                        String contenidoBase64, String idSolicitud, String ip) {
        colas.encolar(Mensaje.builder()
                .tipo(TipoMensaje.MENSAJE_ARCHIVO)
                .id(idSolicitud)
                .fechaHora(LocalDateTime.now().toString())
                .remitente(sesion.codigo())
                .destinatario(destinatario)
                .nombreArchivo(nombreArchivo)
                .mime(mime)
                .contenidoImagen(contenidoBase64)
                .ipRemitente(ip)
                .build());
    }

    public void difundir(IdSesion sesion, String contenido, String idSolicitud, String ip) {
        colas.encolar(Mensaje.builder()
                .tipo(TipoMensaje.BROADCAST)
                .id(idSolicitud)
                .fechaHora(LocalDateTime.now().toString())
                .remitente(sesion.codigo())
                .contenido(contenido)
                .ipRemitente(ip)
                .build());
    }
}
