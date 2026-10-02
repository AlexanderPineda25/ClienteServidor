package universidad.mensajeria.clienteservidor.commands;

import universidad.mensajeria.clienteservidor.SalidaClienteServidor.SalidaMensajeria;
import universidad.mensajeria.clienteservidor.SalidaClienteServidor.SalidaRespuestas;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.clienteservidor.impl.CommandHandler;

/** ARCHIVO_FIN: cierra la transferencia con el sha256 del archivo completo. */
public final class ArchivoFinCommand implements CommandHandler {

    private final SalidaMensajeria salida;
    private final SalidaRespuestas respuestas;

    public ArchivoFinCommand(SalidaMensajeria salida, SalidaRespuestas respuestas) {
        this.salida = salida;
        this.respuestas = respuestas;
    }

    @Override
    public void handle(Mensaje mensaje, IdSesion sesion) {
        if (!Comandos.exigirAutenticacion(mensaje, sesion, respuestas)) {
            return;
        }
        if (!Comandos.exigir(mensaje, sesion, respuestas, mensaje.archivoId(), "archivoId")
                || !Comandos.exigir(mensaje, sesion, respuestas, mensaje.hashSha256(), "hashSha256")) {
            return;
        }
        salida.finalizarArchivo(sesion, mensaje.archivoId(), mensaje.hashSha256(),
                mensaje.id(), sesion.direccionIp());
    }

    @Override
    public String tipo() {
        return "ARCHIVO_FIN";
    }
}
