package universidad.mensajeria.clienteservidor.commands;

import universidad.mensajeria.clienteservidor.SalidaClienteServidor.SalidaMensajeria;
import universidad.mensajeria.clienteservidor.SalidaClienteServidor.SalidaRespuestas;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.common.tipos.TipoMensaje;
import universidad.mensajeria.clienteservidor.impl.CommandHandler;

/** ARCHIVO_PARTE: un bloque base64 de la transferencia indicada por archivoId. */
public final class ArchivoParteCommand implements CommandHandler {

    private final SalidaMensajeria salida;
    private final SalidaRespuestas respuestas;

    public ArchivoParteCommand(SalidaMensajeria salida, SalidaRespuestas respuestas) {
        this.salida = salida;
        this.respuestas = respuestas;
    }

    @Override
    public void handle(Mensaje mensaje, IdSesion sesion) {
        if (!Comandos.exigirAutenticacion(mensaje, sesion, respuestas)) {
            return;
        }
        if (!Comandos.exigir(mensaje, sesion, respuestas, mensaje.archivoId(), "archivoId")
                || !Comandos.exigir(mensaje, sesion, respuestas, mensaje.contenidoImagen(),
                "contenidoImagen en base64")
                || mensaje.indiceParte() == null || mensaje.indiceParte() < 0) {
            return;
        }
        salida.parteArchivo(sesion, mensaje.archivoId(), mensaje.indiceParte(),
                mensaje.contenidoImagen(), mensaje.id(), sesion.direccionIp());
    }

    @Override
    public String tipo() {
        return "ARCHIVO_PARTE";
    }
}
