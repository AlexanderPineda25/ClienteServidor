package universidad.mensajeria.clienteservidor.impl;

import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.tipos.Mensaje;

/**
 * Command del patron Command (PLAN2 §2.5): un tipo de trama = una clase.
 * Valida y delega en la salida que implementa la Fachada; no conoce la red,
 * la persistencia ni otros componentes.
 */
public interface CommandHandler {

    void handle(Mensaje mensaje, IdSesion sesion);

    /** Nombre del bean/tipo del catalogo que atiende (p.ej. "LOGIN"). */
    String tipo();
}
