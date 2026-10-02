package universidad.mensajeria.server.transversal.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * Adaptador de DataSource: construye la conexion segun el motor activo (§5.4).
 * Intercambiar de BD = cambiar db.active + credenciales; esta clase no cambia.
 */
@Configuration
public class DataSourceAdapter {

    @Bean
    public DataSource dataSource(DbProperties db, PoolProperties pool) {
        DbProperties.Motor motor = db.motorActivo();
        if (motor == null || motor.url() == null) {
            throw new IllegalStateException("Falta la configuracion del motor activo: db." + db.nombreActivo());
        }
        String driver = resolveDriver(motor.vendor());

        if (!pool.enabled()) {
            DriverManagerDataSource simple = new DriverManagerDataSource(driver);
            simple.setUrl(motor.url());
            simple.setUsername(motor.user());
            simple.setPassword(motor.pass());
            return simple;
        }

        HikariConfig cfg = new HikariConfig();
        cfg.setDriverClassName(driver);
        cfg.setJdbcUrl(motor.url());
        cfg.setUsername(motor.user());
        cfg.setPassword(motor.pass());
        cfg.setMaximumPoolSize(pool.maximumPoolSize());
        cfg.setMinimumIdle(pool.minimumIdle());
        cfg.setIdleTimeout(pool.idleTimeoutMs());
        cfg.setConnectionTimeout(pool.connectionTimeoutMs());
        cfg.setPoolName("mensajeria-" + db.nombreActivo());
        return new HikariDataSource(cfg);
    }

    private String resolveDriver(String vendor) {
        if (vendor == null) return "com.mysql.cj.jdbc.Driver";
        return switch (vendor.toLowerCase()) {
            case "postgres", "postgresql" -> "org.postgresql.Driver";
            case "oracle" -> "oracle.jdbc.OracleDriver";
            default -> "com.mysql.cj.jdbc.Driver";
        };
    }
}
