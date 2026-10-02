package universidad.mensajeria.cliente.componentes.historial;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import universidad.mensajeria.cliente.persistencia.LocalStore;
import universidad.mensajeria.cliente.transversal.config.ClienteConfig;
import universidad.mensajeria.cliente.transversal.records.MensajeLocal;
import universidad.mensajeria.cliente.transversal.records.PendienteEnvio;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistorialAislamientoCuentaTest {

    @TempDir
    Path directorio;

    @Test
    void migraLegadoUnaVezYConservaPerspectivaIndependientePorCuenta() throws Exception {
        String base = directorio.resolve("mensajeria").toAbsolutePath()
                .toString().replace('\\', '/');
        Properties props = new Properties();
        props.setProperty("db.url", "jdbc:h2:file:" + base + ";AUTO_SERVER=TRUE");
        ClienteConfig config = ClienteConfig.desde(props);
        LocalStore legado = new LocalStore(config);
        LocalStore storeA = new LocalStore(config);
        LocalStore storeB = new LocalStore(config);
        try {
            legado.inicializar();
            HistorialLocal anterior = new HistorialLocal(legado);
            anterior.guardar(mensaje("m1", "A001", "B002", true, "ENVIADO"));
            anterior.guardar(mensaje("m2", "B002", "A001", false, "LEIDO"));
            anterior.guardar(new MensajeLocal("img", "A001", "B002", "MENSAJE_IMAGEN",
                    "cGF5bG9hZA==", "image-hash", null, null, "foto.png", "cache/foto.png",
                    10L, "2026-10-01T09:30:00", true, true, "ENTREGADO", "archivo-9"));
            anterior.registrarPendiente(new PendienteEnvio("p-A", "MENSAJE_TEXTO", "A001",
                    "B002", "pendiente A", null, null, "2026-10-01T10:00:00", 0, null));
            anterior.registrarPendiente(new PendienteEnvio("p-B", "MENSAJE_TEXTO", "B002",
                    "A001", "pendiente B", null, null, "2026-10-01T10:01:00", 0, null));

            HistorialLocal historialA = new HistorialLocal(storeA);
            HistorialLocal historialB = new HistorialLocal(storeB);
            historialA.prepararCuenta("A001");
            historialB.prepararCuenta("B002");

            List<MensajeLocal> paraA = historialA.pagina("A001", "B002", 10, 0);
            List<MensajeLocal> paraB = historialB.pagina("B002", "A001", 10, 0);
            assertEquals(3, paraA.size());
            assertEquals(3, paraB.size());
            assertTrue(paraA.stream().filter(m -> m.idMensaje().equals("m1"))
                    .findFirst().orElseThrow().enviado());
            assertTrue(!paraA.stream().filter(m -> m.idMensaje().equals("m2"))
                    .findFirst().orElseThrow().enviado());
            assertTrue(!paraB.stream().filter(m -> m.idMensaje().equals("m1"))
                    .findFirst().orElseThrow().enviado());
            assertTrue(paraB.stream().filter(m -> m.idMensaje().equals("m2"))
                    .findFirst().orElseThrow().enviado());
            assertTrue(paraA.stream().filter(m -> m.idMensaje().equals("img"))
                    .findFirst().orElseThrow().enviado());
            assertTrue(!paraB.stream().filter(m -> m.idMensaje().equals("img"))
                    .findFirst().orElseThrow().enviado());
            assertEquals("archivo-9", historialB.archivoIdPorId("img").orElseThrow());
            assertEquals("cGF5bG9hZA==", historialA.blobPorId("img").orElseThrow());
            assertEquals(List.of("p-A"), historialA.pendientes().stream()
                    .map(PendienteEnvio::id).toList());
            assertEquals(List.of("p-B"), historialB.pendientes().stream()
                    .map(PendienteEnvio::id).toList());

            historialA.marcarEstado("m1", "LEIDO");
            historialA.prepararCuenta("A001");
            assertEquals("LEIDO", historialA.pagina("A001", "B002", 10, 0).stream()
                    .filter(m -> m.idMensaje().equals("m1")).findFirst().orElseThrow().estado());
            assertEquals("ENVIADO", historialB.pagina("B002", "A001", 10, 0).stream()
                    .filter(m -> m.idMensaje().equals("m1")).findFirst().orElseThrow().estado());
            assertEquals(3, anterior.pagina("A001", "B002", 10, 0).size(),
                    "la base compartida original queda intacta");
        } finally {
            storeB.cerrar();
            storeA.cerrar();
            legado.cerrar();
        }
    }

    @Test
    void dosInstanciasDeLaMismaCuentaCompartenElArchivoDerivado() throws Exception {
        String base = directorio.resolve("misma-cuenta").toAbsolutePath()
                .toString().replace('\\', '/');
        Properties props = new Properties();
        props.setProperty("db.url", "jdbc:h2:file:" + base + ";AUTO_SERVER=TRUE");
        ClienteConfig config = ClienteConfig.desde(props);
        LocalStore storeUno = new LocalStore(config);
        LocalStore storeDos = new LocalStore(config);
        try {
            HistorialLocal uno = new HistorialLocal(storeUno);
            HistorialLocal dos = new HistorialLocal(storeDos);
            uno.prepararCuenta("A001");
            dos.prepararCuenta("A001");
            uno.guardar(mensaje("shared", "A001", "B002", true, "ENVIADO"));

            assertTrue(dos.pagina("A001", "B002", 10, 0).get(0).enviado());
            assertEquals("shared", dos.pagina("A001", "B002", 10, 0).get(0).idMensaje());
        } finally {
            storeDos.cerrar();
            storeUno.cerrar();
        }
    }

    private static MensajeLocal mensaje(String id, String origen, String destino,
                                       boolean enviado, String estado) {
        return new MensajeLocal(id, origen, destino, "MENSAJE_TEXTO", id, null,
                id.length(), 1, null, null, null, "2026-10-01T09:00:00",
                enviado, true, estado, null);
    }
}
