package universidad.mensajeria.cliente.transversal.utilerias;

import org.junit.jupiter.api.Test;
import universidad.mensajeria.cliente.transversal.records.MensajeLocal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormatoRespuestaTest {

    @Test
    void citaTextoEnUnaLineaYConLongitudAcotada() {
        MensajeLocal citado = new MensajeLocal("1", "B", "A", "MENSAJE_TEXTO",
                "Hola\n" + "x".repeat(150), null, null, null, null, null, null,
                "2026-01-01T10:00:00", false, true);

        String prefijo = FormatoRespuesta.prefijo(citado, "Ana Perez [A001]");

        assertTrue(prefijo.startsWith("Respuesta a Ana Perez [A001]: \"Hola "));
        assertTrue(prefijo.endsWith("…\"\n\n"));
        assertEquals(120, prefijo.substring(prefijo.indexOf('"') + 1,
                prefijo.lastIndexOf('"')).codePointCount(0,
                prefijo.substring(prefijo.indexOf('"') + 1,
                        prefijo.lastIndexOf('"')).length()));
    }

    @Test
    void citaImagenUsaElNombreYNoLosBytes() {
        MensajeLocal citado = new MensajeLocal("2", "B", "A", "MENSAJE_IMAGEN",
                "base64-invalido", null, null, null, "foto.png", null, 12L,
                "2026-01-01T10:00:00", false, true);

        assertEquals("Respuesta a Luis [B002]: [Imagen: foto.png]\n\n",
                FormatoRespuesta.prefijo(citado, "Luis [B002]"));
    }
}
