package universidad.mensajeria.cliente.componentes.conexion;

import universidad.mensajeria.common.tipos.Mensaje;

/**
 * Retrollamadas de red hacia la fachada (Observer): la UI nunca toca sockets,
 * solo recibe estos eventos ya en objetos Mensaje.
 */
public interface ReceptorMensajes {

    /** Texto, imagen o broadcast entrante (ya persistido por la fachada). */
    void alRecibir(Mensaje mensaje);

    /** El servidor cerró o expulsó esta sesión. */
    void alCierre(Mensaje aviso);

    /** Error asíncrono (sin solicitud pendiente que lo reclame). */
    void alError(String motivo);

    /** El socket se rompió (corte de red): operar offline. */
    void alDesconexion();
}
