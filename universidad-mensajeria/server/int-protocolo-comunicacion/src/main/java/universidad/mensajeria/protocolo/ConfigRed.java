package universidad.mensajeria.protocolo;

/** Configuracion de red que Fabrica entrega a la implementacion (§2.5). */
public record ConfigRed(int puerto, int poolCore, int poolMax, int poolQueue, int timeoutSegundos,
                        int poolEsperaSegundos, int tramasPorSegundo,
                        boolean tlsHabilitado, int tlsPuerto, String tlsAlmacen, String tlsClave) {

    /** Compatibilidad: TLS apagado, 10 tramas/s. */
    public ConfigRed(int puerto, int poolCore, int poolMax, int poolQueue, int timeoutSegundos,
                     int poolEsperaSegundos, int tramasPorSegundo) {
        this(puerto, poolCore, poolMax, poolQueue, timeoutSegundos, poolEsperaSegundos,
                tramasPorSegundo, false, 5001, null, null);
    }

    /** Compatibilidad: sin espera = rechazo inmediato; 10 tramas/s por defecto. */
    public ConfigRed(int puerto, int poolCore, int poolMax, int poolQueue, int timeoutSegundos,
                     int poolEsperaSegundos) {
        this(puerto, poolCore, poolMax, poolQueue, timeoutSegundos, poolEsperaSegundos, 10);
    }

    /** Compatibilidad: sin espera = rechazo inmediato al saturar (comportamiento historico). */
    public ConfigRed(int puerto, int poolCore, int poolMax, int poolQueue, int timeoutSegundos) {
        this(puerto, poolCore, poolMax, poolQueue, timeoutSegundos, 0);
    }
}
