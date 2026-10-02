package universidad.mensajeria.cliente.componentes.historial;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import universidad.mensajeria.cliente.persistencia.LocalStore;
import universidad.mensajeria.cliente.transversal.config.ClienteConfig;
import universidad.mensajeria.cliente.transversal.records.MensajeLocal;
import universidad.mensajeria.cliente.transversal.records.PendienteEnvio;

import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Queries canónicas de HISTORIAL_LOCAL.md sobre H2 en memoria
 * (mismo esquema y SQL que SQLite en Python/C#, §5.2).
 */
class HistorialLocalTest {

    private HistorialLocal historial;

    @BeforeEach
    void montar() throws Exception {
        Properties props = new Properties();
        props.setProperty("db.url", "jdbc:h2:mem:hist" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1");
        LocalStore store = new LocalStore(ClienteConfig.desde(props));
        store.inicializar();
        historial = new HistorialLocal(store);
    }

    private static MensajeLocal texto(String id, String origen, String destino, String fecha) {
        return new MensajeLocal(id, origen, destino, "MENSAJE_TEXTO", "hola",
                "hash", 4, 1, null, null, null, fecha, true, true);
    }

    @Test
    void paginaConversacionFiltraAmbosSentidosOrdenReciente() throws Exception {
        historial.guardar(texto("m1", "A", "B", "2026-01-01T10:00:00"));
        historial.guardar(texto("m2", "B", "A", "2026-01-01T10:05:00"));
        historial.guardar(texto("m3", "A", "C", "2026-01-01T10:10:00"));

        List<MensajeLocal> pagina = historial.pagina("A", "B", 10, 0);

        assertEquals(2, pagina.size());
        assertEquals("m2", pagina.get(0).idMensaje());
        assertEquals("m1", pagina.get(1).idMensaje());
        assertEquals(2, historial.contar("A", "B"));
        assertEquals(1, historial.contar("A", "C"));
    }

    @Test
    void upsertSobreescribeMismoId() throws Exception {
        historial.guardar(texto("m1", "A", "B", "2026-01-01T10:00:00"));
        historial.guardar(new MensajeLocal("m1", "A", "B", "MENSAJE_TEXTO", "editado",
                "hash2", 7, 1, null, null, null, "2026-01-01T10:00:00", true, true));

        List<MensajeLocal> pagina = historial.pagina("A", "B", 10, 0);

        assertEquals(1, pagina.size());
        // FASE 13.9: la lista es metadata (sin blob); el contenido va por blobPorId.
        assertEquals("m1", pagina.get(0).idMensaje());
        assertEquals("editado", historial.blobPorId("m1").orElseThrow());
        assertTrue(historial.blobPorId("inexistente").isEmpty());
    }

    @Test
    void marcarDescargadoGuardaRuta() throws Exception {
        historial.guardar(new MensajeLocal("img1", "A", "B", "MENSAJE_IMAGEN", null,
                "hash", null, null, "foto.png", null, 12L, "2026-01-01T10:00:00", false, false));

        historial.marcarDescargado("img1", "/tmp/foto.png");

        MensajeLocal fila = historial.pagina("A", "B", 10, 0).get(0);
        assertTrue(fila.descargado());
        assertEquals("/tmp/foto.png", fila.rutaArchivo());
    }

    @Test
    void cicloDeVidaDePendientes() throws Exception {        assertTrue(historial.pendientes().isEmpty());

        historial.registrarPendiente(new PendienteEnvio("p1", "MENSAJE_TEXTO", "A", "B",
                "hola", null, null, "2026-01-01T10:00:00", 0, null));
        assertEquals(1, historial.pendientes().size());

        historial.incrementarIntento("p1", "sin red");
        PendienteEnvio pendiente = historial.pendientePorId("p1").orElseThrow();
        assertEquals(1, pendiente.intentos());
        assertEquals("sin red", pendiente.ultimoError());

        historial.eliminarPendiente("p1");
        assertTrue(historial.pendientes().isEmpty());
        assertTrue(historial.pendientePorId("p1").isEmpty());
    }

    @Test
    void recientesDevuelvePreviewSinBlobCompleto() throws Exception {        // FASE 13.4: la UI muestra 32 caracteres o "[imagen] nombre"; traer el
        // base64 completo (MB) por conversación en cada refresco es lo que
        // congelaba el cliente.
        String blob = "a".repeat(200_000);
        historial.guardar(new MensajeLocal("img9", "B", "A", "MENSAJE_IMAGEN", blob,
                "hash9", null, null, "foto.png", null, 150_000L,
                "2026-01-01T10:00:00", false, true));
        historial.guardar(new MensajeLocal("t9", "C", "A", "MENSAJE_TEXTO",
                "x".repeat(500), "hashT", 500, 1, null, null, null,
                "2026-01-01T11:00:00", false, true));

        var filas = historial.recientes("A");

        assertEquals(2, filas.size());
        var imagen = filas.stream().filter(r -> r.otro().equals("B")).findFirst().orElseThrow();
        assertEquals("[imagen] foto.png", imagen.ultimoContenido(),
                "preview de imagen sin transferir el blob");
        var texto = filas.stream().filter(r -> r.otro().equals("C")).findFirst().orElseThrow();
        assertTrue(texto.ultimoContenido().length() <= 160,
                "preview de texto recortado, no el contenido completo");
    }

    @Test
    void rutaDeDatosCompletaRapidaConBlobsDe1MB() throws Exception {
        // FASE 13.14: lo que el refresco toca en H2 (recientes + página + conteo
        // + blob puntual) debe resolverse en ms aunque haya 20 imágenes de 1 MB.
        // (La red quedó fuera del refresco; esto blinda la parte local.)
        String blob = "b".repeat(1024 * 1024);
        for (int i = 0; i < 20; i++) {
            historial.guardar(new MensajeLocal("big-" + i, "B", "A", "MENSAJE_IMAGEN", blob,
                    "h" + i, null, null, "f" + i + ".png", null, (long) blob.length(),
                    "2026-01-0" + (i % 9 + 1) + "T10:00:00", false, true));
        }

        long inicio = System.nanoTime();
        var recientes = historial.recientes("A");
        var pagina = historial.pagina("A", "B", 50, 0);
        int total = historial.contar("A", "B");
        var puntual = historial.blobPorId("big-0");
        long ms = (System.nanoTime() - inicio) / 1_000_000;

        assertEquals(1, recientes.size());
        assertEquals(20, pagina.size());
        assertEquals(20, total);
        assertTrue(puntual.isPresent());
        assertTrue(ms < 3000, "ruta H2 con 20 MB en blobs: " + ms + "ms");
    }

    @Test
    void paginaTraeTextoPeroNoBlobsDeImagen() throws Exception {
        // FASE 13.15: el NULL plano dejaba burbujas de texto vacías; el CASE
        // solo anula el contenido de imágenes.
        historial.guardar(new MensajeLocal("t1", "A", "B", "MENSAJE_TEXTO", "hola",
                "h", 4, 1, null, null, null, "2026-01-01T10:00:00", true, true));
        historial.guardar(new MensajeLocal("i1", "B", "A", "MENSAJE_IMAGEN", "blob",
                "h2", null, null, "f.png", null, 4L, "2026-01-01T11:00:00", false, true));

        var pagina = historial.pagina("A", "B", 10, 0);

        assertEquals("blob", historial.blobPorId("i1").orElseThrow());
        var img = pagina.stream().filter(m -> m.idMensaje().equals("i1")).findFirst().orElseThrow();
        assertEquals(null, img.contenido(), "la lista no arrastra el blob");
        var txt = pagina.stream().filter(m -> m.idMensaje().equals("t1")).findFirst().orElseThrow();
        assertEquals("hola", txt.contenido(), "el texto sí viaja para la burbuja");
    }

    @Test
    void archivoIdSobreviveLecturaLocalYLosEstadosDeAcuseSonMonotonicos() throws Exception {
        historial.guardar(new MensajeLocal("img-old", "B", "A", "MENSAJE_IMAGEN", null,
                "h", null, null, "antigua.png", null, 12L, "2026-01-01T10:00:00",
                false, false, "ENTREGADO", "archivo-77"));

        assertEquals("archivo-77", historial.pagina("A", "B", 10, 0).get(0).archivoId());
        assertEquals("archivo-77", historial.archivoIdPorId("img-old").orElseThrow());
        historial.marcarEstado("img-old", "LEIDO");
        historial.marcarEstado("img-old", "ENTREGADO");
        assertEquals("LEIDO", historial.pagina("A", "B", 10, 0).get(0).estado());
    }

    @Test
    void consultaIdsNoLeidosSoloIncluyeEntrantesPendientes() throws Exception {
        historial.guardar(new MensajeLocal("in-1", "B", "A", "MENSAJE_TEXTO", "hola",
                null, 4, 1, null, null, null, "2026-01-01T10:00:00", false, true,
                "ENTREGADO"));
        historial.guardar(new MensajeLocal("out-1", "A", "B", "MENSAJE_TEXTO", "hola",
                null, 4, 1, null, null, null, "2026-01-01T10:01:00", true, true,
                "ENVIADO"));

        assertEquals(List.of("in-1"), historial.idsNoLeidos("A", "B"));
    }
}
