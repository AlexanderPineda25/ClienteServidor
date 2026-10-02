package universidad.mensajeria.clienteservidor.commands;

import universidad.mensajeria.clienteservidor.SalidaClienteServidor.SalidaMensajeria;
import universidad.mensajeria.clienteservidor.SalidaClienteServidor.SalidaRespuestas;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.common.tipos.TipoMensaje;
import universidad.mensajeria.clienteservidor.impl.CommandHandler;

/** MENSAJE_TEXTO: exige auth + destinatario y delega (la Fachada procesa, persiste y entrega). */
public final class TextoCommand implements CommandHandler {

    private final SalidaMensajeria salida;
    private final SalidaRespuestas respuestas;

    public TextoCommand(SalidaMensajeria salida, SalidaRespuestas respuestas) {
        this.salida = salida;
        this.respuestas = respuestas;
    }

    @Override
    public void handle(Mensaje mensaje, IdSesion sesion) {
        if (!Comandos.exigirAutenticacion(mensaje, sesion, respuestas)) {
            return;
        }
        if (mensaje.remitente() != null && !mensaje.remitente().equals(sesion.codigo())) {
            respuestas.error(sesion, mensaje.id(), TipoMensaje.ERROR,
                    "el remitente debe ser tu codigo logueado");
            return;
        }
        if (!Comandos.exigir(mensaje, sesion, respuestas, mensaje.destinatario(), "destinatario")
                || !Comandos.exigir(mensaje, sesion, respuestas, mensaje.contenido(), "contenido")) {
            return;
        }
        salida.recibirTexto(sesion, mensaje.destinatario(), mensaje.contenido(),
                mensaje.id(), sesion.direccionIp());
    }

    @Override
    public String tipo() {
        return "MENSAJE_TEXTO";
    }
}
