package universidad.mensajeria.server.vistaconsola;

import org.junit.jupiter.api.Test;
import universidad.mensajeria.common.interno.InformeDTO;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Render ASCII puro de los informes (FASE 9, §8): sin red, sin BD. */
class TablaAsciiTest {

    @Test
    void pintaTituloEncabezadosFilasYConteo() {
        InformeDTO informe = new InformeDTO("Informe 1 — Prueba",
                List.of("Codigo", "Nombre"),
                List.of(List.of("A001", "Ana"), List.of("B002", "Bea")));

        String tabla = TablaAscii.convertir(informe);

        assertTrue(tabla.contains("Informe 1 — Prueba"));
        assertTrue(tabla.contains("| Codigo | Nombre |"));
        assertTrue(tabla.contains("| A001   | Ana    |"));
        assertTrue(tabla.contains("| B002   | Bea    |"));
        assertTrue(tabla.contains("2 filas"));
        assertTrue(tabla.startsWith("Informe 1 — Prueba"), "el titulo abre la tabla");
        assertTrue(tabla.lines().skip(1).findFirst().orElse("").startsWith("+"),
                "borde superior en la linea 2");
        assertFalse(tabla.contains("null"), "las celdas null nunca llegan a consola");
    }

    @Test
    void celdaLargaSeRecortaConTresPuntos() {
        String hash = "a".repeat(64);
        InformeDTO informe = new InformeDTO("Informe", List.of("Hash"),
                List.of(List.of(hash)));

        String tabla = TablaAscii.convertir(informe);

        assertTrue(tabla.contains("aaa"), "la celda conserva su inicio");
        assertTrue(tabla.contains("..."), "se recorta a 60 caracteres");
        assertFalse(tabla.contains(hash), "el hash completo no se imprime");
    }

    @Test
    void informeSinFilasLoDice() {
        InformeDTO informe = new InformeDTO("Informe vacio", List.of("Col"),
                List.of());

        String tabla = TablaAscii.convertir(informe);

        assertTrue(tabla.contains("0 filas"));
    }
}
