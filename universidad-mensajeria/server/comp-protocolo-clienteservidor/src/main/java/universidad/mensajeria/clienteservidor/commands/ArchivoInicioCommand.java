package universidad.mensajeria.clienteservidor.commands;

import universidad.mensajeria.clienteservidor.SalidaClienteServidor.SalidaMensajeria;
import universidad.mensajeria.clienteservidor.SalidaClienteServidor.SalidaRespuestas;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.common.tipos.TipoMensaje;
import universidad.mensajeria.clienteservidor.impl.CommandHandler;

/** ARCHIVO_INICIO (archivos fragmentados 50 MB, PROTOCOLO.md §3): anuncia y delega. */
public final class ArchivoInicioCommand implements CommandHandler {

    private final SalidaMensajeria salida;
    private final SalidaRespuestas respuestas;

    public ArchivoInicioCommand(SalidaMensajeria salida, SalidaRespuestas respuestas) {
        this.salida = salida;
        this.respuestas = respuestas;
    }

    @Override
    public void handle(Mensaje mensaje, IdSesion sesion) {
        if (!Comandos.exigirAutenticacion(mensaje, sesion, respuestas)) {
            return;
        }
        if (!Comandos.exigir(mensaje, sesion, respuestas, mensaje.destinatario(), "destinatario")
                || !Comandos.exigir(mensaje, sesion, respuestas, mensaje.nombreArchivo(), "nombreArchivo")
                || mensaje.tamanoArchivo() == null || mensaje.tamanoArchivo() <= 0
                || mensaje.totalPartes() == null || mensaje.totalPartes() <= 0) {
            respuestas.error(sesion, mensaje.id(), TipoMensaje.ERROR,
                    "ARCHIVO_INICIO exige destinatario, nombreArchivo, tamanoArchivo y totalPartes");
            return;
        }
        salida.iniciarArchivo(sesion, mensaje.destinatario(), mensaje.nombreArchivo(),
                mensaje.mime(), mensaje.tamanoArchivo(), mensaje.totalPartes(), mensaje.id(),
                sesion.direccionIp());
    }

    @Override
    public String tipo() {
        return "ARCHIVO_INICIO";
    }
}
