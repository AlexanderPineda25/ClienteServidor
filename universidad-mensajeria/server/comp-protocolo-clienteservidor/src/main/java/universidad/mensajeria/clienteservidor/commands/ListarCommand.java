package universidad.mensajeria.clienteservidor.commands;

import universidad.mensajeria.clienteservidor.SalidaClienteServidor.SalidaConsultas;
import universidad.mensajeria.clienteservidor.SalidaClienteServidor.SalidaRespuestas;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.clienteservidor.impl.CommandHandler;

/** LISTAR_CONECTADOS: delega el listado de sesiones vivas. */
public final class ListarCommand implements CommandHandler {

    private final SalidaConsultas salida;

    public ListarCommand(SalidaConsultas salida, SalidaRespuestas respuestas) {
        this.salida = salida;
    }

    @Override
    public void handle(Mensaje mensaje, IdSesion sesion) {
        salida.listarConectados(sesion, mensaje.id());
    }

    @Override
    public String tipo() {
        return "LISTAR_CONECTADOS";
    }
}
