package universidad.mensajeria.clienteservidor.commands;

import universidad.mensajeria.clienteservidor.SalidaClienteServidor.SalidaMensajeria;
import universidad.mensajeria.clienteservidor.SalidaClienteServidor.SalidaRespuestas;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.clienteservidor.impl.CommandHandler;

/** MENSAJE_ARCHIVO (§13.15, tuberia generica §3.2): valida y delega. */
public final class ArchivoCommand implements CommandHandler {

    private final SalidaMensajeria salida;
    private final SalidaRespuestas respuestas;

    public ArchivoCommand(SalidaMensajeria salida, SalidaRespuestas respuestas) {
        this.salida = salida;
        this.respuestas = respuestas;
    }

    @Override
    public void handle(Mensaje mensaje, IdSesion sesion) {
        if (!Comandos.exigirAutenticacion(mensaje, sesion, respuestas)) {
            return;
        }
        if (!Comandos.exigir(mensaje, sesion, respuestas, mensaje.destinatario(), "destinatario")
                || !Comandos.exigir(mensaje, sesion, respuestas, mensaje.contenidoImagen(),
                "contenidoImagen en base64")) {
            return;
        }
        salida.recibirArchivo(sesion, mensaje.destinatario(), mensaje.nombreArchivo(),
                mensaje.mime(), mensaje.contenidoImagen(), mensaje.id(), sesion.direccionIp());
    }

    @Override
    public String tipo() {
        return "MENSAJE_ARCHIVO";
    }
}
