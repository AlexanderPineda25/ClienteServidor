package universidad.mensajeria.clienteservidor.commands;

import universidad.mensajeria.clienteservidor.SalidaClienteServidor.SalidaMensajeria;
import universidad.mensajeria.clienteservidor.SalidaClienteServidor.SalidaRespuestas;
import universidad.mensajeria.clienteservidor.impl.CommandHandler;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.common.tipos.TipoMensaje;

/** MENSAJE_LEIDO: el lector avisa al autor; se reenvía sin cola. */
public final class LeidoCommand implements CommandHandler {

    private final SalidaMensajeria salida;
    private final SalidaRespuestas respuestas;

    public LeidoCommand(SalidaMensajeria salida, SalidaRespuestas respuestas) {
        this.salida = salida;
        this.respuestas = respuestas;
    }

    @Override
    public void handle(Mensaje mensaje, IdSesion sesion) {
        if (!Comandos.exigirAutenticacion(mensaje, sesion, respuestas)) {
            return;
        }
        if (!Comandos.exigir(mensaje, sesion, respuestas, mensaje.destinatario(), "destinatario")
                || !Comandos.exigir(mensaje, sesion, respuestas, mensaje.contenido(), "contenido")) {
            return;
        }
        salida.notificarLectura(sesion, mensaje.destinatario(), mensaje.contenido(), mensaje.id());
    }

    @Override
    public String tipo() {
        return "MENSAJE_LEIDO";
    }
}
