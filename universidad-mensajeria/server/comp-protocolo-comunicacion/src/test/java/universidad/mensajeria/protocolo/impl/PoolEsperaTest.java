package universidad.mensajeria.protocolo.impl;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Espera acotada por trabajador libre (RF-S46, segunda opcion). */
class PoolEsperaTest {

    private static PoolDeTrabajadores poolDeUno() {
        return new FabricaPoolDeTrabajadores().crear(1, new ConcurrentHashMap<>(),
                () -> null, 300);
    }

    @Test
    void adquirirInmediatoAgotadoDevuelveNull() {
        PoolDeTrabajadores pool = poolDeUno();
        ManejadorConexion ocupado = pool.adquirir();
        try {
            assertNull(pool.adquirir());
        } finally {
            pool.liberar(ocupado);
            pool.cerrar();
        }
    }

    @Test
    void adquirirConEsperaObtieneElTrabajadorLiberado() throws Exception {
        PoolDeTrabajadores pool = poolDeUno();
        ManejadorConexion ocupado = pool.adquirir();
        AtomicReference<ManejadorConexion> obtenido = new AtomicReference<>();
        AtomicBoolean listo = new AtomicBoolean();
        Thread espera = new Thread(() -> {
            obtenido.set(pool.adquirir(5, TimeUnit.SECONDS));
            listo.set(true);
        });
        try {
            espera.start();
            Thread.sleep(200);
            pool.liberar(ocupado);
            espera.join(6000);
            assertTrue(listo.get(), "la espera no obtuvo trabajador");
            assertNotNull(obtenido.get());
        } finally {
            ManejadorConexion resto = obtenido.get();
            if (resto != null) {
                pool.liberar(resto);
            }
            pool.cerrar();
        }
    }

    @Test
    void adquirirConEsperaAgotadaDevuelveNull() {
        PoolDeTrabajadores pool = poolDeUno();
        ManejadorConexion ocupado = pool.adquirir();
        try {
            assertNull(pool.adquirir(100, TimeUnit.MILLISECONDS));
        } finally {
            pool.liberar(ocupado);
            pool.cerrar();
        }
    }
}
