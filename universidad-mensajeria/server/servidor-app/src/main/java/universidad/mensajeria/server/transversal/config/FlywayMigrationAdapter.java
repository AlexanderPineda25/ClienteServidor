package universidad.mensajeria.server.transversal.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Adaptador Flyway (§5.4): inyecta las migraciones del motor activo antes de
 * migrar. El esquema lo manda SOLO Flyway (ddl-auto=none), nunca Hibernate.
 */
@Configuration
public class FlywayMigrationAdapter {

    @Bean
    @ConditionalOnProperty(prefix = "flyway", name = "enabled", havingValue = "true", matchIfMissing = true)
    public FlywayConfigurationCustomizer flywayDelMotorActivo(DbProperties db, FlywayGroupProperties flyway) {
        return configuracion -> configuracion.locations(flyway.locationsDelMotor(db.nombreActivo()));
    }
}
