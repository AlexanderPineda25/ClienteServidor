package universidad.mensajeria.server.transversal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propiedades de red, limites de conexiones y directorio de subidas.
 * Origen: server.properties (§10 "Se crea": max_conexiones, puerto, uploadDir).
 * Ningun valor esta hardcodeado: cambiar el comportamiento es solo editar el archivo.
 */
@ConfigurationProperties(prefix = "red")
public record RedProperties(int puerto, Pool pool, Limites limites, String uploadDir, Tls tls) {

    public record Tls(boolean habilitado, int puerto, String almacen, String clave) {
        public Tls {
            if (puerto <= 0 || puerto > 65535) {
                puerto = 5001;
            }
        }
    }

    public record Pool(int core, int max, int queue, int esperaSegundos, int tramasPorSegundo) {
        public Pool {
            if (esperaSegundos <= 0) {
                esperaSegundos = 5;
            }
            if (tramasPorSegundo <= 0) {
                tramasPorSegundo = 10;
            }
        }
    }

    public record Limites(int maxConexiones, int maxConexionesPorUsuario) {
    }

    public RedProperties {
        if (puerto <= 0 || puerto > 65535) {
            puerto = 5000;
        }
        if (tls == null) {
            tls = new Tls(false, 5001, null, null);
        }
        if (limites == null) {
            limites = new Limites(100, 3);
        } else {
            limites = new Limites(Math.max(0, limites.maxConexiones()),
                    Math.max(0, limites.maxConexionesPorUsuario()));
        }
        int maxTrabajadores = pool == null ? 50 : Math.max(1, pool.max());
        if (limites.maxConexiones() > 0) maxTrabajadores = limites.maxConexiones();
        int core = pool == null ? Math.min(10, maxTrabajadores)
                : Math.min(maxTrabajadores, Math.max(1, pool.core()));
        pool = new Pool(core, maxTrabajadores,
                pool == null ? 100 : Math.max(1, pool.queue()),
                pool == null ? 5 : pool.esperaSegundos(),
                pool == null ? 10 : pool.tramasPorSegundo());
        if (uploadDir == null || uploadDir.isBlank()) {
            uploadDir = "uploads";
        }
    }
}
