package universidad.mensajeria.cliente.componentes.historial;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import universidad.mensajeria.cliente.persistencia.LocalStore;
import universidad.mensajeria.cliente.transversal.config.ClienteConfig;
import universidad.mensajeria.cliente.transversal.records.MensajeLocal;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistorialMultiinstanciaTest {

    @TempDir
    Path directorio;

    @Test
    void dosStoresCompartenHistorialEnH2AutoServer() throws Exception {
        String archivo = directorio.resolve("mensajeria").toAbsolutePath()
                .toString().replace('\\', '/');
        Properties propiedades = new Properties();
        propiedades.setProperty("db.url", "jdbc:h2:file:" + archivo + ";AUTO_SERVER=TRUE");
        ClienteConfig config = ClienteConfig.desde(propiedades);

        LocalStore primero = new LocalStore(config);
        LocalStore segundo = new LocalStore(config);
        try {
            primero.inicializar();
            segundo.inicializar();

            HistorialLocal historialUno = new HistorialLocal(primero);
            HistorialLocal historialDos = new HistorialLocal(segundo);
            historialUno.guardar(new MensajeLocal("multi-1", "A", "B", "MENSAJE_TEXTO",
                    "hola", "hash", 4, 1, null, null, null,
                    "2026-10-01T10:00:00", true, true));

            List<MensajeLocal> compartido = historialDos.pagina("A", "B", 10, 0);
            assertEquals(1, compartido.size());
            assertEquals("multi-1", compartido.get(0).idMensaje());
            assertEquals("hola", historialDos.blobPorId("multi-1").orElseThrow());
        } finally {
            segundo.cerrar();
            primero.cerrar();
        }
    }

    @Test
    void dosProcesosJVMCompartenElMismoArchivoH2() throws Exception {
        String archivo = directorio.resolve("mensajeria-procesos").toAbsolutePath()
                .toString().replace('\\', '/');
        String url = "jdbc:h2:file:" + archivo + ";AUTO_SERVER=TRUE";
        Properties propiedades = new Properties();
        propiedades.setProperty("db.url", url);
        LocalStore store = new LocalStore(ClienteConfig.desde(propiedades));
        try {
            store.inicializar();
            HistorialLocal historial = new HistorialLocal(store);
            historial.guardar(mensaje("padre-1", "escrito por el padre"));

            String java = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").toLowerCase().contains("win")
                            ? "java.exe" : "java").toString();
            String classpath = System.getProperty("surefire.test.class.path",
                    System.getProperty("java.class.path"));
            Process proceso = new ProcessBuilder(java, "-cp", classpath,
                    H2ProcessProbe.class.getName(), url)
                    .redirectErrorStream(true).start();
            boolean termino = proceso.waitFor(20, TimeUnit.SECONDS);
            if (!termino) {
                proceso.destroyForcibly().waitFor();
            }
            String salida = new String(proceso.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(termino, "la JVM hija no terminó: " + salida);
            assertEquals(0, proceso.exitValue(), salida);
            assertEquals("escrito por la JVM hija",
                    historial.blobPorId("hija-1").orElseThrow());
        } finally {
            store.cerrar();
        }
    }

    static MensajeLocal mensaje(String id, String texto) {
        return new MensajeLocal(id, "A", "B", "MENSAJE_TEXTO", texto, "hash",
                texto.length(), 1, null, null, null, "2026-10-01T10:00:00", true, true);
    }
}
