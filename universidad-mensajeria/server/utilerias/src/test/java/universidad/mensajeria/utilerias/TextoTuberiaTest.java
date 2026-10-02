package universidad.mensajeria.utilerias;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tuberia de texto: SHA256 → ContarCaracteres → ContarPalabras (§3.2). */
class TextoTuberiaTest {

    @TempDir
    Path trabajo;

    @Test
    void hashConocidoYConteosExactos() throws Exception {
        File original = trabajo.resolve("msg.txt").toFile();
        Files.writeString(original.toPath(), "hola mundo", StandardCharsets.UTF_8);

        Tuberia.ResultadoTuberia resultado = new TuberiaFactory().texto()
                .ejecutar(List.of(original), ContextoTuberia.sinOyente(trabajo));

        // echo -n "hola mundo" | sha256sum
        assertEquals("0b894166d3336435c800bea36ff21b29eaa801a52f584c006c49289a0dcf6e2f",
                resultado.metadatos().obtener("hashSha256"));
        assertEquals("10", resultado.metadatos().obtener("numCaracteres"));
        assertEquals("2", resultado.metadatos().obtener("numPalabras"));
        assertEquals(List.of("SHA256", "ContarCaracteres", "ContarPalabras"),
                resultado.traza().stream().map(e -> e.etapa()).toList());
    }
}
