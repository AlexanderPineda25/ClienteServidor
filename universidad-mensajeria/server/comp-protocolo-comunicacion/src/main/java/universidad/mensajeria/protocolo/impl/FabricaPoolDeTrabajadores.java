package universidad.mensajeria.protocolo.impl;

import universidad.mensajeria.protocolo.ReceptorDeTramas;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

final class FabricaPoolDeTrabajadores {

    PoolDeTrabajadores crear(int cantidad, ConcurrentHashMap<String, SesionTcp> sesiones,
                             Supplier<ReceptorDeTramas> receptor, int timeoutSegundos) {
        return crear(cantidad, sesiones, receptor, timeoutSegundos, 0, 10);
    }

    PoolDeTrabajadores crear(int cantidad, ConcurrentHashMap<String, SesionTcp> sesiones,
                             Supplier<ReceptorDeTramas> receptor, int timeoutSegundos,
                             int esperaSegundos) {
        return crear(cantidad, sesiones, receptor, timeoutSegundos, esperaSegundos, 10);
    }

    PoolDeTrabajadores crear(int cantidad, ConcurrentHashMap<String, SesionTcp> sesiones,
                             Supplier<ReceptorDeTramas> receptor, int timeoutSegundos,
                             int esperaSegundos, int tramasPorSegundo) {
        int capacidad = Math.max(1, cantidad);
        AtomicInteger numero = new AtomicInteger();
        PoolDeTrabajadoresImpl pool = new PoolDeTrabajadoresImpl(capacidad, sesiones, receptor,
                timeoutSegundos, tramasPorSegundo, numero);
        pool.esperaSegundos(esperaSegundos);
        return pool;
    }
}
