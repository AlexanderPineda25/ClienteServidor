package universidad.mensajeria.clienteservidor.commands;

import universidad.mensajeria.clienteservidor.SalidaClienteServidor.SalidaMensajeria;
import universidad.mensajeria.clienteservidor.SalidaClienteServidor.SalidaRespuestas;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.clienteservidor.impl.CommandHandler;

/** DESCARGAR_ARCHIVO (§4.2): exige auth + archivoId y delega la descarga. */
public final class DescargaCommand implements CommandHandler {

    private final SalidaMensajeria salida;
    private final SalidaRespuestas respuestas;

    public DescargaCommand(SalidaMensajeria salida, SalidaRespuestas respuestas) {
        this.salida = salida;
        this.respuestas = respuestas;
    }

    @Override
    public void handle(Mensaje mensaje, IdSesion sesion) {
        if (!Comandos.exigirAutenticacion(mensaje, sesion, respuestas)) {
            return;
        }
        if (!Comandos.exigir(mensaje, sesion, respuestas, mensaje.archivoId(), "archivoId")) {
            return;
        }
        salida.descargar(sesion, mensaje.archivoId(), mensaje.id());
    }

    @Override
    public String tipo() {
        return "DESCARGAR_ARCHIVO";
    }
}
