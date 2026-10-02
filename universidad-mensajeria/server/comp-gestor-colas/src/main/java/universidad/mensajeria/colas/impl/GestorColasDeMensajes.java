package universidad.mensajeria.colas.impl;

import universidad.mensajeria.colas.ConsumidorDeMensajes;
import universidad.mensajeria.colas.InterfazGestorMensajes;
import universidad.mensajeria.colas.ProveedorGestorMensajes;
import universidad.mensajeria.common.tipos.Mensaje;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Cola + indice en memoria (§5.3): BlockingQueue + ConcurrentHashMap por id
 * de trama. Al encolar avisa a los consumidores registrados (la Fachada
 * encadena Mensajes.procesarX).
 */
public final class GestorColasDeMensajes implements InterfazGestorMensajes {

    private final LinkedBlockingQueue<Mensaje> colaProcesamiento = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<String, Mensaje> indice = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<ConsumidorDeMensajes> consumidores = new CopyOnWriteArrayList<>();

    @Override
    public void encolar(Mensaje mensaje) {
        Mensaje conId = mensaje.id() == null || mensaje.id().isBlank()
                ? Mensaje.builder().tipo(mensaje.tipo()).id(UUID.randomUUID().toString()).build()
                : mensaje;
        indice.put(conId.id(), conId);
        colaProcesamiento.add(conId);
        for (ConsumidorDeMensajes consumidor : consumidores) {
            consumidor.procesar(conId);
        }
    }

    @Override
    public Optional<Mensaje> buscarPorId(String id) {
        return Optional.ofNullable(indice.get(id));
    }

    @Override
    public List<Mensaje> conversacion(String origen, String destino, int pagina, int tamano) {
        List<Mensaje> filtrados = colaProcesamiento.stream()
                .filter(m -> esDe(m, origen, destino) || esDe(m, destino, origen))
                .toList();
        int desde = Math.min(pagina * tamano, filtrados.size());
        int hasta = Math.min(desde + tamano, filtrados.size());
        return filtrados.subList(desde, hasta);
    }

    private static boolean esDe(Mensaje m, String origen, String destino) {
        return origen.equals(m.remitente()) && destino.equals(m.destinatario());
    }

    @Override
    public int tamano() {
        return colaProcesamiento.size();
    }

    @Override
    public void limpiar() {
        colaProcesamiento.clear();
        indice.clear();
    }

    @Override
    public void registrarConsumidor(ConsumidorDeMensajes consumidor) {
        consumidores.add(consumidor);
    }

    /** Registro SPI (PLAN2 §2.4). */
    public static final class Proveedor implements ProveedorGestorMensajes {

        @Override
        public InterfazGestorMensajes crear() {
            return new GestorColasDeMensajes();
        }
    }
}
