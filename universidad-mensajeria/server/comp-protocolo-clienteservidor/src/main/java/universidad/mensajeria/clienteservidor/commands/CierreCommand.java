package universidad.mensajeria.clienteservidor.commands;

import universidad.mensajeria.clienteservidor.SalidaClienteServidor.SalidaAutenticacion;
import universidad.mensajeria.clienteservidor.SalidaClienteServidor.SalidaRespuestas;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.common.tipos.TipoMensaje;
import universidad.mensajeria.clienteservidor.impl.CommandHandler;

/**
 * KICK (§4.1, FASE 4): auto-kick de las DEMAS sesiones del propio codigo.
 * Expulsar otro codigo responde ERROR (kick administrativo en FASE 10).
 */
public final class CierreCommand implements CommandHandler {

    private final SalidaAutenticacion salida;
    private final SalidaRespuestas respuestas;

    public CierreCommand(SalidaAutenticacion salida, SalidaRespuestas respuestas) {
        this.salida = salida;
        this.respuestas = respuestas;
    }

    @Override
    public void handle(Mensaje mensaje, IdSesion sesion) {
        if (!Comandos.exigirAutenticacion(mensaje, sesion, respuestas)) {
            return;
        }
        if (!Comandos.exigir(mensaje, sesion, respuestas, mensaje.codigo(), "codigo")) {
            return;
        }
        if (!mensaje.codigo().equals(sesion.codigo())) {
            respuestas.error(sesion, mensaje.id(), TipoMensaje.ERROR,
                    "solo puedes cerrar tus propias sesiones");
            return;
        }
        salida.expulsarOtrasSesiones(sesion, mensaje.codigo(), mensaje.id());
    }

    @Override
    public String tipo() {
        return "KICK";
    }
}
