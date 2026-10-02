package universidad.mensajeria.colas;

import universidad.mensajeria.common.tipos.Mensaje;

import java.util.List;
import java.util.Optional;

/**
 * Cola de mensajes en memoria (PLAN2 §2.5): encolar, buscar por id,
 * conversacion paginada y consumidor de procesamiento.
 */
public interface InterfazGestorMensajes {

    void encolar(Mensaje mensaje);

    Optional<Mensaje> buscarPorId(String id);

    List<Mensaje> conversacion(String origen, String destino, int pagina, int tamano);

    int tamano();

    void limpiar();

    void registrarConsumidor(ConsumidorDeMensajes consumidor);
}
