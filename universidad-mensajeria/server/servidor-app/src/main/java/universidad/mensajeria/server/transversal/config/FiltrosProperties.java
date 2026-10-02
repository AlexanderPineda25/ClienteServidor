package universidad.mensajeria.server.transversal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parametros de tuberias (PLAN2 §3.3-§3.4): pool entre-mensajes y factores
 * visuales. Todo por server.properties, sin hardcode.
 */
@ConfigurationProperties(prefix = "filtros")
public record FiltrosProperties(int hilos, int giroGrados, double brilloFactor,
                                double reduccionFactor) {

    public FiltrosProperties {
        if (hilos <= 0) {
            hilos = 4;
        }
        if (giroGrados % 90 != 0) {
            giroGrados = 180;
        }
        if (brilloFactor <= 0) {
            brilloFactor = 1.25;
        }
        if (reduccionFactor <= 0 || reduccionFactor >= 1) {
            reduccionFactor = 0.5;
        }
    }
}
