package universidad.mensajeria.clienteservidor.commands;

import universidad.mensajeria.clienteservidor.SalidaClienteServidor.SalidaAutenticacion;
import universidad.mensajeria.clienteservidor.SalidaClienteServidor.SalidaRespuestas;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.clienteservidor.impl.CommandHandler;

/** LOGOUT: delega el cierre en la Fachada (avisa CLOSE_NOTICE a sus sockets). */
public final class LogoutCommand implements CommandHandler {

    private final SalidaAutenticacion salida;

    public LogoutCommand(SalidaAutenticacion salida, SalidaRespuestas respuestas) {
        this.salida = salida;
    }

    @Override
    public void handle(Mensaje mensaje, IdSesion sesion) {
        salida.cerrar(sesion, mensaje.id());
    }

    @Override
    public String tipo() {
        return "LOGOUT";
    }
}
