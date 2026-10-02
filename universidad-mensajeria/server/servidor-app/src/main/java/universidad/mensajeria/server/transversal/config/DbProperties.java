package universidad.mensajeria.server.transversal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propiedades de conexion multi-motor (ADAPTER §5.4).
 * El motor activo se elige con db.active=mysql|postgres|oracle.
 */
@ConfigurationProperties(prefix = "db")
public record DbProperties(String active, Motor mysql, Motor postgres, Motor oracle) {

    public record Motor(String vendor, String url, String user, String pass) {
    }

    public String nombreActivo() {
        return active == null || active.isBlank() ? "mysql" : active.trim();
    }

    public Motor motorActivo() {
        return switch (nombreActivo()) {
            case "mysql" -> mysql;
            case "postgres" -> postgres;
            case "oracle" -> oracle;
            default -> throw new IllegalStateException(
                    "db.active invalido: " + active + " (esperado: mysql|postgres|oracle)");
        };
    }
}
