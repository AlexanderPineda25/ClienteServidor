package universidad.mensajeria.protocolo;

import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.interno.EstadoPool;
import universidad.mensajeria.common.tipos.Mensaje;

/**
 * Transporte y tramas (PLAN2 §2.5): iniciar/detener el puerto, enviar a una
 * sesion, cerrarla y registrar el receptor de tramas (callback que implementa
 * la Fachada). Los sockets jamas salen de este componente (Regla 4).
 */
public interface InterfazProtocoloComunicacion {

    void iniciar();

    void detener();

    boolean estaActivo();

    int puerto();

    EstadoPool estadoPool();

    void enviar(IdSesion sesion, Mensaje mensaje) throws Exception;

    /**
     * Marca la sesion como autenticada (Regla 4: el estado vive en el
     * transporte). Devuelve la identidad actualizada para registrarla.
     */
    IdSesion autenticar(IdSesion sesion, String codigo);

    void cerrarSesion(IdSesion sesion, String motivo);

    void registrarReceptor(ReceptorDeTramas receptor);
}
