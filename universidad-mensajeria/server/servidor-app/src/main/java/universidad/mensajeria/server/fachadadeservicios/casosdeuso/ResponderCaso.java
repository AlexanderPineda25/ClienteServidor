package universidad.mensajeria.server.fachadadeservicios.casosdeuso;

import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.common.tipos.TipoMensaje;

import java.time.LocalDateTime;

/** Respuestas correlacionadas (id + fechaHora de la solicitud). */
public final class ResponderCaso {

    private final universidad.mensajeria.protocolo.InterfazProtocoloComunicacion protocolo;

    public ResponderCaso(universidad.mensajeria.protocolo.InterfazProtocoloComunicacion protocolo) {
        this.protocolo = protocolo;
    }

    public void responder(IdSesion sesion, Mensaje respuesta) {
        try {
            protocolo.enviar(sesion, respuesta);
        } catch (Exception e) {
            // sesion muerta: el transporte la poda al detectar el corte
        }
    }

    public void exito(IdSesion sesion, String idSolicitud, TipoMensaje tipo) {
        responder(sesion, Mensaje.builder()
                .tipo(tipo)
                .id(idSolicitud)
                .fechaHora(LocalDateTime.now().toString())
                .exito(true)
                .build());
    }

    public void fallo(IdSesion sesion, Mensaje solicitud, TipoMensaje tipo, String motivo) {
        responder(sesion, Mensaje.builder()
                .tipo(tipo)
                .id(solicitud.id())
                .fechaHora(LocalDateTime.now().toString())
                .exito(false)
                .mensajeError(motivo)
                .build());
    }

    public void fallo(IdSesion sesion, String idSolicitud, TipoMensaje tipo, String motivo) {
        responder(sesion, Mensaje.builder()
                .tipo(tipo)
                .id(idSolicitud)
                .fechaHora(LocalDateTime.now().toString())
                .exito(false)
                .mensajeError(motivo)
                .build());
    }
}
