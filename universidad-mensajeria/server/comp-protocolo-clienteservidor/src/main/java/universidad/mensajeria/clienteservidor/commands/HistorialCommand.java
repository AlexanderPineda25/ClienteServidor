package universidad.mensajeria.clienteservidor.commands;

import universidad.mensajeria.clienteservidor.SalidaClienteServidor.SalidaMensajeria;
import universidad.mensajeria.clienteservidor.SalidaClienteServidor.SalidaRespuestas;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.clienteservidor.impl.CommandHandler;

/** HISTORIAL_REQ (§4.2): exige auth + destinatario y delega la pagina. */
public final class HistorialCommand implements CommandHandler {

    private final SalidaMensajeria salida;
    private final SalidaRespuestas respuestas;

    public HistorialCommand(SalidaMensajeria salida, SalidaRespuestas respuestas) {
        this.salida = salida;
        this.respuestas = respuestas;
    }

    @Override
    public void handle(Mensaje mensaje, IdSesion sesion) {
        if (!Comandos.exigirAutenticacion(mensaje, sesion, respuestas)) {
            return;
        }
        if (!Comandos.exigir(mensaje, sesion, respuestas, mensaje.destinatario(), "destinatario")) {
            return;
        }
        int pagina = mensaje.pagina() != null ? Math.max(0, mensaje.pagina()) : 0;
        salida.historial(sesion, mensaje.destinatario(), pagina, mensaje.id());
    }

    @Override
    public String tipo() {
        return "HISTORIAL_REQ";
    }
}
