package universidad.mensajeria.server.inicio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RutasProyectoTest {

    @TempDir
    Path temporal;

    @Test
    void resuelveDirectoriosDeEjecucionBajoServerEnEsteMonorepo() {
        Path raiz = RutasProyecto.buscarRaiz(Path.of("").toAbsolutePath()).orElseThrow();

        assertEquals(raiz.resolve("server/uploads"), RutasProyecto.resolver(Path.of("uploads")));
        assertEquals(raiz.resolve("server/logs/server.log"),
                RutasProyecto.resolver(Path.of("logs/server.log")));
    }

    @Test
    void resuelveRutasRelativasDesdeLaRaizAunqueElServidorArranqueEnUnModulo() throws Exception {
        Path raiz = temporal.resolve("proyecto");
        Path moduloServidor = raiz.resolve("server/servidor-app");
        Files.createDirectories(moduloServidor);
        Files.createDirectories(raiz.resolve("client"));
        Files.createDirectories(raiz.resolve("common-protocol"));

        assertEquals(raiz.resolve("server/uploads"),
                RutasProyecto.resolverDesde(moduloServidor, Path.of("uploads")));
        assertEquals(raiz.resolve("server/logs/server.log"),
                RutasProyecto.resolverDesde(moduloServidor, Path.of("logs/server.log")));
    }

    @Test
    void mantieneLasRutasAbsolutas() {
        Path absoluta = temporal.resolve("externo/uploads").toAbsolutePath();

        assertEquals(absoluta, RutasProyecto.resolver(absoluta));
    }
}
