package universidad.mensajeria.clienteservidor.commands;

import universidad.mensajeria.clienteservidor.SalidaClienteServidor.SalidaConsultas;
import universidad.mensajeria.clienteservidor.SalidaClienteServidor.SalidaRespuestas;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.clienteservidor.impl.CommandHandler;

/** LISTAR_USUARIOS (§13.15): directorio completo con estado. */
public final class ListarUsuariosCommand implements CommandHandler {

    private final SalidaConsultas salida;

    public ListarUsuariosCommand(SalidaConsultas salida, SalidaRespuestas respuestas) {
        this.salida = salida;
    }

    @Override
    public void handle(Mensaje mensaje, IdSesion sesion) {
        salida.listarUsuarios(sesion, mensaje.id());
    }

    @Override
    public String tipo() {
        return "LISTAR_USUARIOS";
    }
}
