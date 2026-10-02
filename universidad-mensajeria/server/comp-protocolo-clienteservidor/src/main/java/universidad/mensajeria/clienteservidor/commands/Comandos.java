package universidad.mensajeria.clienteservidor.commands;

import universidad.mensajeria.clienteservidor.SalidaClienteServidor.SalidaRespuestas;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.common.tipos.TipoMensaje;

/** Guardias compartidos: autenticacion y campos obligatorios (responden por SalidaRespuestas). */
final class Comandos {

    private Comandos() {
    }

    static boolean exigirAutenticacion(Mensaje solicitud, IdSesion sesion, SalidaRespuestas respuestas) {
        if (sesion.autenticada()) {
            return true;
        }
        respuestas.error(sesion, solicitud.id(), TipoMensaje.ERROR, "autenticate primero con LOGIN");
        return false;
    }

    static boolean exigir(Mensaje solicitud, IdSesion sesion, SalidaRespuestas respuestas,
                          String valor, String campo) {
        if (valor != null && !valor.isBlank()) {
            return true;
        }
        respuestas.error(sesion, solicitud.id(), TipoMensaje.ERROR, campo + " es obligatorio");
        return false;
    }
}
