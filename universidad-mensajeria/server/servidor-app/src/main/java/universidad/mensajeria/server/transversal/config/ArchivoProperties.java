package universidad.mensajeria.server.transversal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Techo de configuracion para archivos fragmentados (tope propio 50 MB).
 * El limite operativo vive en configuracion_limites (V2: 50 MB); el
 * efectivo es el menor de ambos.
 */
@ConfigurationProperties(prefix = "archivo")
public record ArchivoProperties(int maxMb) {

    public ArchivoProperties {
        if (maxMb <= 0) {
            maxMb = 50;
        }
    }

    public long maxBytes() {
        return (long) maxMb * 1024L * 1024L;
    }
}
