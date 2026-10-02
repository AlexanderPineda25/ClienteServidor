package universidad.mensajeria.server.transversal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Localiza las migraciones Flyway del motor activo (ADAPTER §5.4). */
@ConfigurationProperties(prefix = "flyway")
public record FlywayGroupProperties(boolean enabled, Locations mysql, Locations postgres, Locations oracle) {

    public record Locations(String locations) {
    }

    public FlywayGroupProperties {
        if (mysql == null) mysql = new Locations("classpath:db/migration/mysql");
        if (postgres == null) postgres = new Locations("classpath:db/migration/postgres");
        if (oracle == null) oracle = new Locations("classpath:db/migration/oracle");
    }

    public String locationsDelMotor(String motorActivo) {
        return switch (motorActivo) {
            case "postgres" -> postgres.locations();
            case "oracle" -> oracle.locations();
            default -> mysql.locations();
        };
    }
}
