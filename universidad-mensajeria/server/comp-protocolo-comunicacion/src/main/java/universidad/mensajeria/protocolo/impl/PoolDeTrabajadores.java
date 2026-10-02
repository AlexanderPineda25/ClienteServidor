package universidad.mensajeria.protocolo.impl;

import universidad.mensajeria.common.interno.EstadoPool;

interface PoolDeTrabajadores {

    ManejadorConexion adquirir();

    /** Espera hasta timeout por un trabajador libre; null si se agota (RF-S46). */
    ManejadorConexion adquirir(long timeout, java.util.concurrent.TimeUnit unidad);

    void liberar(ManejadorConexion trabajador);

    EstadoPool estado();

    void cerrar();
}
