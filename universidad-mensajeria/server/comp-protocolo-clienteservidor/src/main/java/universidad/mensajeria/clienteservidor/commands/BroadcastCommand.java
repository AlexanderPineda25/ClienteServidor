package universidad.mensajeria.clienteservidor.commands;

import universidad.mensajeria.clienteservidor.SalidaClienteServidor.SalidaMensajeria;
import universidad.mensajeria.clienteservidor.SalidaClienteServidor.SalidaRespuestas;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.clienteservidor.impl.CommandHandler;

/** BROADCAST (req. #9): exige auth + contenido y delega la difusion. */
public final class BroadcastCommand implements CommandHandler {

    private final SalidaMensajeria salida;
    private final SalidaRespuestas respuestas;

    public BroadcastCommand(SalidaMensajeria salida, SalidaRespuestas respuestas) {
        this.salida = salida;
        this.respuestas = respuestas;
    }

    @Override
    public void handle(Mensaje mensaje, IdSesion sesion) {
        if (!Comandos.exigirAutenticacion(mensaje, sesion, respuestas)) {
            return;
        }
        if (!Comandos.exigir(mensaje, sesion, respuestas, mensaje.contenido(), "contenido")) {
            return;
        }
        salida.difundir(sesion, mensaje.contenido(), mensaje.id(), sesion.direccionIp());
    }

    @Override
    public String tipo() {
        return "BROADCAST";
    }
}
