package universidad.mensajeria.server.transversal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Limites del pool de conexiones (archivo de configuracion, req. servidor #2). */
@ConfigurationProperties(prefix = "pool")
public record PoolProperties(boolean enabled, int maximumPoolSize, int minimumIdle,
                             long idleTimeoutMs, long connectionTimeoutMs) {

    public PoolProperties {
        if (maximumPoolSize <= 0) maximumPoolSize = 10;
        if (minimumIdle < 0) minimumIdle = 2;
        if (idleTimeoutMs <= 0) idleTimeoutMs = 30_000L;
        if (connectionTimeoutMs <= 0) connectionTimeoutMs = 30_000L;
    }
}
