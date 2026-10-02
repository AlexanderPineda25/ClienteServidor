package universidad.mensajeria.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import universidad.mensajeria.server.inicio.RutasProyecto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Arranque del servidor (Spring Boot). El arranque funcional (semilla, red TCP,
 * vistas) lo dirige {@code presentacion.InicioServidor} según el diagrama §2.1:
 * InicioServidor → VistaConsola (+ VistaEscritorio desde la FASE 9),
 * todo hablando con FachadaDeServicios.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ServerApplication {

    public static void main(String[] args) throws IOException {
        if (System.getProperty("logging.file.name") == null) {
            Path archivoLog = RutasProyecto.resolver(Path.of("logs", "server.log"));
            Files.createDirectories(archivoLog.getParent());
            System.setProperty("logging.file.name", archivoLog.toString());
        }
        SpringApplication.run(ServerApplication.class, args);
    }
}
