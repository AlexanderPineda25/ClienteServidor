package universidad.mensajeria.utilerias;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import universidad.mensajeria.utilerias.imagen.Imagenes;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tuberia de imagen: 6 etapas secuenciales, cada derivada parte de la anterior (§3.3). */
class ImagenTuberiaTest {

    @TempDir
    Path trabajo;

    private File originalPng() throws Exception {
        BufferedImage imagen = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                imagen.setRGB(x, y, ((x * 32) << 16) | ((y * 32) << 8) | 128);
            }
        }
        File original = trabajo.resolve("original.png").toFile();
        try (var out = Files.newOutputStream(original.toPath())) {
            ImageIO.write(imagen, "png", out);
        }
        return original;
    }

    @Test
    void seisEtapasEnOrdenYDerivadasAcumuladas() throws Exception {
        File original = originalPng();

        Tuberia.ResultadoTuberia resultado = new TuberiaFactory().imagen()
                .ejecutar(List.of(original), ContextoTuberia.sinOyente(trabajo));

        assertEquals(List.of("SHA256", "EscalaGrises", "Sepia", "Giro180", "Brillo", "Reduccion"),
                resultado.traza().stream().map(e -> e.etapa()).toList());
        assertTrue(resultado.metadatos().obtener("hashSha256").matches("[0-9a-f]{64}"));

        // La salida final es la reduccion (4x4 desde 8x8).
        BufferedImage reducida = Imagenes.leer(resultado.archivos().get(0).toPath());
        assertEquals(4, reducida.getWidth());
        assertEquals(4, reducida.getHeight());

        for (String archivo : List.of("01_grises.png", "02_sepia.png", "03_giro180.png",
                "04_brillo.png", "05_reduccion.png")) {
            assertTrue(Files.isRegularFile(trabajo.resolve(archivo)), "falta " + archivo);
        }
    }

    @Test
    void archivoGenericoSoloCalculaHash() throws Exception {
        File documento = trabajo.resolve("doc.pdf").toFile();
        byte[] bytes = "contenido-falso-de-pdf".getBytes();
        Files.write(documento.toPath(), bytes);

        Tuberia.ResultadoTuberia resultado = new TuberiaFactory().archivo()
                .ejecutar(List.of(documento), ContextoTuberia.sinOyente(trabajo));

        assertEquals(List.of(documento), resultado.archivos());
        assertEquals(1, resultado.traza().size());
        assertTrue(resultado.metadatos().obtener("hashSha256").matches("[0-9a-f]{64}"));

        try (ByteArrayOutputStream control = new ByteArrayOutputStream()) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] esperado = digest.digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : esperado) {
                hex.append(String.format("%02x", b));
            }
            assertEquals(hex.toString(), resultado.metadatos().obtener("hashSha256"));
        }
    }
}
