package universidad.mensajeria.clienteservidor;

import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.tipos.Mensaje;

/**
 * Despacho de tramas por tipo (PLAN2 §2.5): valida y delega en la salida que
 * implementa la Fachada. No conoce la red ni a otros componentes.
 */
public interface InterfazClienteServidor {

    void procesar(Mensaje mensaje, IdSesion sesion);

    void registrarSalida(SalidaClienteServidor salida);
}
