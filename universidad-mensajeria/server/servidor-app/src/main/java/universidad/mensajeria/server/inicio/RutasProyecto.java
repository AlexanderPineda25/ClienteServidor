package universidad.mensajeria.server.inicio;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class RutasProyecto {

    private RutasProyecto() {
    }

    public static Path resolver(Path ruta) {
        if (ruta.isAbsolute()) {
            return ruta.normalize();
        }

        Path directorioActual = Path.of("").toAbsolutePath().normalize();
        Path ubicacion = ubicacionClase();
        Optional<Path> raiz = buscarRaiz(directorioActual);
        if (raiz.isEmpty()) {
            raiz = buscarRaiz(ubicacion);
        }
        Path directorioServidor = raiz.map(path -> path.resolve("server"))
                .orElseGet(() -> Files.isRegularFile(ubicacion)
                        ? ubicacion.getParent() : directorioActual);
        return directorioServidor.resolve(ruta).normalize();
    }

    static Path resolverDesde(Path desde, Path ruta) {
        if (ruta.isAbsolute()) {
            return ruta.normalize();
        }
        Path base = desde.toAbsolutePath().normalize();
        return buscarRaiz(base).map(raiz -> raiz.resolve("server"))
                .orElse(base).resolve(ruta).normalize();
    }

    static Optional<Path> buscarRaiz(Path desde) {
        Path actual = Files.isRegularFile(desde) ? desde.getParent() : desde;
        while (actual != null) {
            if (Files.isDirectory(actual.resolve("client"))
                    && Files.isDirectory(actual.resolve("server"))
                    && Files.isDirectory(actual.resolve("common-protocol"))) {
                return Optional.of(actual);
            }
            actual = actual.getParent();
        }
        return Optional.empty();
    }

    private static Path ubicacionClase() {
        try {
            return Path.of(InicioServidor.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
        } catch (URISyntaxException | RuntimeException e) {
            return Path.of("").toAbsolutePath().normalize();
        }
    }
}
