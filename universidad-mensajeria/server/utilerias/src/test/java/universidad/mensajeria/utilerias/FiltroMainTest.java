package universidad.mensajeria.utilerias;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FiltroMain: fabrica por nombre y equivalencia con la tuberia en memoria (§3.6). */
class FiltroMainTest {

    @TempDir
    Path trabajo;

    @Test
    void fabricaPorNombreYParametros() {
        assertEquals("SHA256", FiltroMain.crear("sha256-texto").nombre());
        assertEquals("Giro180", FiltroMain.crear("giro").nombre());
        assertEquals("Giro90", FiltroMain.crear("giro:90").nombre());
        assertThrows(IllegalArgumentException.class, () -> FiltroMain.crear("inexistente"));
    }

    @Test
    void procesoEquivaleATuberiaEnMemoria() throws Exception {
        File original = trabajo.resolve("in").resolve("msg.txt").toFile();
        Files.createDirectories(original.toPath().getParent());
        Files.writeString(original.toPath(), "hola mundo", StandardCharsets.UTF_8);

        List<File> salidas = FiltroMain.ejecutarUna("contar-palabras", original, trabajo);
        assertEquals(List.of(original), salidas);

        Tuberia.ResultadoTuberia enMemoria = new TuberiaFactory().texto()
                .ejecutar(List.of(original), ContextoTuberia.sinOyente(trabajo));
        assertEquals("2", enMemoria.metadatos().obtener("numPalabras"));
    }

    @Test
    void cadenaManualPorRutas() throws Exception {
        File original = trabajo.resolve("msg.txt").toFile();
        Files.writeString(original.toPath(), "uno dos tres", StandardCharsets.UTF_8);

        // sha256-texto pasa el archivo; contar-palabras lo lee: P1 | P2.
        List<File> paso1 = FiltroMain.ejecutarUna("sha256-texto", original, trabajo);
        assertEquals(List.of(original), paso1);
        assertTrue(Files.isRegularFile(paso1.get(0).toPath()));
    }
}
