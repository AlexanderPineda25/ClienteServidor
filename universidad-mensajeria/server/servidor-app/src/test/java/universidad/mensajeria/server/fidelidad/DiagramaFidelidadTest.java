package universidad.mensajeria.server.fidelidad;

import org.junit.jupiter.api.Test;
import universidad.mensajeria.clienteservidor.ProveedorClienteServidor;
import universidad.mensajeria.clienteservidor.impl.Despachador;
import universidad.mensajeria.colas.ProveedorGestorMensajes;
import universidad.mensajeria.common.tipos.TipoMensaje;
import universidad.mensajeria.protocolo.ProveedorProtocoloComunicacion;
import universidad.mensajeria.servicios.ProveedorServiciosDisponibles;
import universidad.mensajeria.usuarios.ProveedorUsuariosDisponibles;

import java.io.IOException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fidelidad al diagrama (PLAN2 §2.0/§13.16): los 5 comp-* existen y son
 * unicos via ServiceLoader, el despachador cubre el catalogo con handlers y
 * las direcciones de compilacion se respetan (sin Spring en comp-*, sin
 * referencias a implementaciones desde servidor-app, sin paquete dominio).
 */
class DiagramaFidelidadTest {

    /** Base del repo: server/servidor-app/.. (working dir de surefire). */
    private static final Path SERVER =
            Path.of(System.getProperty("user.dir")).getParent();

    @Test
    void cincoComponentesUnicosPorServiceLoader() {
        assertEquals(1, contar(ServiceLoader.load(ProveedorProtocoloComunicacion.class)));
        assertEquals(1, contar(ServiceLoader.load(ProveedorClienteServidor.class)));
        assertEquals(1, contar(ServiceLoader.load(ProveedorServiciosDisponibles.class)));
        assertEquals(1, contar(ServiceLoader.load(ProveedorUsuariosDisponibles.class)));
        assertEquals(1, contar(ServiceLoader.load(ProveedorGestorMensajes.class)));
    }

    private static <T> int contar(ServiceLoader<T> loader) {
        List<T> encontrados = new ArrayList<>();
        loader.forEach(encontrados::add);
        return encontrados.size();
    }

    @Test
    void despachadorCubreCatalogo() {
        Set<String> tipos = Set.copyOf(Despachador.tiposSoportados());
        assertEquals(Despachador.mapaTipos().keySet(), tipos);
        for (TipoMensaje requerido : EnumSet.of(
                TipoMensaje.LOGIN, TipoMensaje.LOGOUT,
                TipoMensaje.LISTAR_CONECTADOS, TipoMensaje.LISTAR_USUARIOS,
                TipoMensaje.MENSAJE_TEXTO, TipoMensaje.MENSAJE_IMAGEN,
                TipoMensaje.MENSAJE_ARCHIVO, TipoMensaje.BROADCAST,
                TipoMensaje.HISTORIAL_REQ, TipoMensaje.DESCARGAR_ARCHIVO,
                TipoMensaje.KICK)) {
            assertTrue(tipos.contains(requerido.name()), "sin handler: " + requerido);
        }
    }

    @Test
    void compUtileriasDtoEIntSinSpring() throws IOException {
        List<String> modulos = List.of("comp-protocolo-comunicacion",
                "comp-protocolo-clienteservidor", "comp-servicios", "comp-usuarios",
                "comp-gestor-colas", "utilerias", "int-protocolo-comunicacion",
                "int-cliente-servidor", "int-servicios-disponibles",
                "int-usuarios-disponibles", "int-gestor-mensajes");
        for (String modulo : modulos) {
            Path base = SERVER.resolve(modulo).resolve("src/main/java");
            try (Stream<Path> archivos = Files.walk(base)) {
                List<Path> violaciones = archivos
                        .filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> contiene(p, "import org.springframework"))
                        .toList();
                assertTrue(violaciones.isEmpty(),
                        modulo + " importa Spring: " + violaciones);
            }
        }
    }

    @Test
    void servidorAppNoReferenciaImplementacionesNiDominio() throws IOException {
        Path base = SERVER.resolve("servidor-app/src/main/java");
        try (Stream<Path> archivos = Files.walk(base)) {
            List<String> violaciones = archivos
                    .filter(p -> p.toString().endsWith(".java"))
                    .flatMap(p -> lineas(p).stream()
                            .filter(l -> l.contains(".impl.")
                                    || l.contains("server.dominio.")
                                    || l.contains("componentes.protocolo.")
                                    || l.contains("componentes.clienteservidor.")
                                    || l.contains("componentes.servicios.")
                                    || l.contains("componentes.usuarios.")
                                    || l.contains("componentes.colas."))
                            .map(l -> p.getFileName() + ": " + l.strip()))
                    .toList();
            assertTrue(violaciones.isEmpty(), "fugas a implementaciones: " + violaciones);
        }
        assertTrue(Files.notExists(
                SERVER.resolve("servidor-app/src/main/java/universidad/mensajeria/server/dominio")),
                "paquete dominio prohibido (PLAN2 §2.6)");
    }

    @Test
    void carpetasEspejoDelDiagrama() {
        // Cada nodo-paquete del PNG tiene su carpeta en servidor-app (§2.2).
        List<String> nodos = List.of("inicio", "vistaconsola", "vistaescritorio",
                "fachadadeservicios", "mensajes", "logdeeventos", "conexionesclientes",
                "almacenarinformacion", "persistencia");
        Path base = SERVER.resolve("servidor-app/src/main/java/universidad/mensajeria/server");
        for (String nodo : nodos) {
            assertTrue(Files.isDirectory(base.resolve(nodo)), "falta carpeta-nodo: " + nodo);
        }
        // Agrupamientos por capas (presentacion/, fachada/, nucleo/) prohibidos.
        assertTrue(Files.notExists(base.resolve("presentacion")), "presentacion/ debe ser vistaconsola/ + vistaescritorio/");
        assertTrue(Files.notExists(base.resolve("fachada")), "fachada/ debe ser fachadadeservicios/");
        assertTrue(Files.notExists(base.resolve("nucleo")), "nucleo/ debe ser mensajes/ + logdeeventos/ + conexionesclientes/ + almacenarinformacion/");
        // DTO-Records vive como proyecto compartido (el cliente Java compila contra el).
        assertTrue(Files.isDirectory(SERVER.getParent().resolve(
                "common-protocol/dto/src/main/java/universidad/mensajeria/common")),
                "DTO-Records debe existir como modulo dto");
    }

    @Test
    void soloPersistenciaVeEntidades() throws IOException {
        // E15 estricto: el adaptador dentro de persistencia es el unico que conoce JPA.
        List<String> duenos = List.of("persistencia");
        Path base = SERVER.resolve("servidor-app/src/main/java/universidad/mensajeria/server");
        try (Stream<Path> archivos = Files.walk(base)) {
            List<String> violaciones = archivos
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> duenos.stream().noneMatch(d ->
                            p.toString().contains(File.separator + d + File.separator)))
                    .flatMap(p -> lineas(p).stream()
                            .filter(l -> l.startsWith("import ")
                                    && l.contains("persistencia.entidades."))
                            .map(l -> p.getFileName() + ": " + l.strip()))
                    .toList();
            assertTrue(violaciones.isEmpty(),
                    "fugas a persistencia.entidades fuera de E15: " + violaciones);
        }
        Path puerto = base.resolve("almacenarinformacion/PersistenciaPort.java");
        assertTrue(Files.isRegularFile(puerto));
        assertTrue(lineas(puerto).stream().noneMatch(l -> l.contains("org.springframework")
                        || l.contains("persistencia.entidades")),
                "PersistenciaPort debe exponer solo DTOs y valores simples");
    }

    private static boolean contiene(Path archivo, String texto) {
        return lineas(archivo).stream().anyMatch(l -> l.contains(texto));
    }

    private static List<String> lineas(Path archivo) {
        try {
            return Files.readAllLines(archivo);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer " + archivo, e);
        }
    }
}
