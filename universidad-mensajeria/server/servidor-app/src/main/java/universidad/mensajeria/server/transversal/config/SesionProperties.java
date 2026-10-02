package universidad.mensajeria.server.transversal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tiempos de sesión TCP (FASE 4: cierre por inactividad; por defecto 600 s = 10 min, P3).
 * Clase separada de RedProperties a propósito (OCP): agregar una familia de
 * claves nueva no toca el record existente ni sus tests.
 */
@ConfigurationProperties(prefix = "red.sesion")
public record SesionProperties(int timeoutSegundos) {

    public SesionProperties {
        if (timeoutSegundos <= 0) {
            timeoutSegundos = 600;
        }
    }
}
