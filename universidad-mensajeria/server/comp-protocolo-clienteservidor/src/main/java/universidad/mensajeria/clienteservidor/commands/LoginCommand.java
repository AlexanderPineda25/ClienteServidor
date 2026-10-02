package universidad.mensajeria.clienteservidor.commands;

import universidad.mensajeria.clienteservidor.SalidaClienteServidor.SalidaAutenticacion;
import universidad.mensajeria.clienteservidor.SalidaClienteServidor.SalidaRespuestas;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.clienteservidor.impl.CommandHandler;

/** LOGIN: valida presencia y delega en SalidaAutenticacion (la Fachada aplica limites y BCrypt). */
public final class LoginCommand implements CommandHandler {

    private final SalidaAutenticacion salida;
    private final SalidaRespuestas respuestas;

    public LoginCommand(SalidaAutenticacion salida, SalidaRespuestas respuestas) {
        this.salida = salida;
        this.respuestas = respuestas;
    }

    @Override
    public void handle(Mensaje mensaje, IdSesion sesion) {
        if (!Comandos.exigir(mensaje, sesion, respuestas, mensaje.codigo(), "codigo")
                || !Comandos.exigir(mensaje, sesion, respuestas, mensaje.contrasena(), "contrasena")) {
            return;
        }
        salida.autenticar(sesion, mensaje.codigo(), mensaje.contrasena(), mensaje.id());
    }

    @Override
    public String tipo() {
        return "LOGIN";
    }
}
