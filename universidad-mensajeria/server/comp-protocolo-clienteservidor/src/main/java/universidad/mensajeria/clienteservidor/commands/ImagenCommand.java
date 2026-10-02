package universidad.mensajeria.clienteservidor.commands;

import universidad.mensajeria.clienteservidor.SalidaClienteServidor.SalidaMensajeria;
import universidad.mensajeria.clienteservidor.SalidaClienteServidor.SalidaRespuestas;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.common.tipos.TipoMensaje;
import universidad.mensajeria.clienteservidor.impl.CommandHandler;

/** MENSAJE_IMAGEN: exige auth + destinatario + base64 y delega (tuberia en la Fachada). */
public final class ImagenCommand implements CommandHandler {

    private final SalidaMensajeria salida;
    private final SalidaRespuestas respuestas;

    public ImagenCommand(SalidaMensajeria salida, SalidaRespuestas respuestas) {
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
        try {
            java.util.Base64.getDecoder().decode(mensaje.contenidoImagen());
        } catch (IllegalArgumentException e) {
            respuestas.error(sesion, mensaje.id(), TipoMensaje.ERROR,
                    "contenidoImagen no es base64 valido");
            return;
        }
        salida.recibirImagen(sesion, mensaje.destinatario(), mensaje.nombreArchivo(),
                mensaje.mime(), mensaje.contenidoImagen(), mensaje.id(), sesion.direccionIp());
    }

    @Override
    public String tipo() {
        return "MENSAJE_IMAGEN";
    }
}
