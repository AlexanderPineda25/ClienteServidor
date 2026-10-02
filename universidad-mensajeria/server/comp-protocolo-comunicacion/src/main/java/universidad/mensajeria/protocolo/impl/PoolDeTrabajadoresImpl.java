package universidad.mensajeria.protocolo.impl;

import universidad.mensajeria.common.interno.EstadoPool;
import universidad.mensajeria.protocolo.ReceptorDeTramas;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

final class PoolDeTrabajadoresImpl implements PoolDeTrabajadores {

    private final int capacidad;
    private final ConcurrentHashMap<String, SesionTcp> sesiones;
    private final ArrayBlockingQueue<ManejadorConexion> disponibles;
    private final List<ManejadorConexion> trabajadores;
    private volatile int esperaSegundos;

    void esperaSegundos(int segundos) {
        this.esperaSegundos = Math.max(0, segundos);
    }

    int esperaSegundos() {
        return esperaSegundos;
    }

    PoolDeTrabajadoresImpl(int capacidad, ConcurrentHashMap<String, SesionTcp> sesiones,
                           Supplier<ReceptorDeTramas> receptor, int timeoutSegundos,
                           int tramasPorSegundo, AtomicInteger numero) {
        this.capacidad = capacidad;
        this.sesiones = sesiones;
        this.disponibles = new ArrayBlockingQueue<>(capacidad);
        this.trabajadores = new ArrayList<>(capacidad);
        for (int i = 0; i < capacidad; i++) {
            ManejadorConexion trabajador = new ManejadorConexion(
                    "red-cliente-" + numero.incrementAndGet(), sesiones, receptor,
                    timeoutSegundos, tramasPorSegundo, this);
            trabajadores.add(trabajador);
            disponibles.add(trabajador);
            trabajador.start();
        }
    }

    @Override
    public ManejadorConexion adquirir() {
        return disponibles.poll();
    }

    @Override
    public ManejadorConexion adquirir(long timeout, TimeUnit unidad) {
        try {
            return disponibles.poll(timeout, unidad);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @Override
    public void liberar(ManejadorConexion trabajador) {
        if (trabajador != null && trabajador.reiniciable()) {
            disponibles.offer(trabajador);
        }
    }

    @Override
    public EstadoPool estado() {
        int libres = disponibles.size();
        return new EstadoPool(capacidad - libres, libres, capacidad);
    }

    @Override
    public void cerrar() {
        trabajadores.forEach(ManejadorConexion::detener);
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (ManejadorConexion trabajador : trabajadores) {
            long restante = limite - System.nanoTime();
            if (restante <= 0) break;
            try {
                trabajador.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(restante)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        disponibles.clear();
    }
}
