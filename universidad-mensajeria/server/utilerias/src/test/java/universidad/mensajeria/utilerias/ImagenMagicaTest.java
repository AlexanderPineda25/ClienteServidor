package universidad.mensajeria.utilerias;

import org.junit.jupiter.api.Test;
import universidad.mensajeria.utilerias.imagen.Imagenes;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FASE 11.2: magic bytes PNG/JPEG/GIF/BMP antes de la tuberia. */
class ImagenMagicaTest {

    @Test
    void aceptaCabecerasConocidas() {
        assertTrue(Imagenes.esImagenValida(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x00}));
        assertTrue(Imagenes.esImagenValida(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00}));
        assertTrue(Imagenes.esImagenValida(new byte[]{0x47, 0x49, 0x46, 0x38, 0x39, 0x61}));
        assertTrue(Imagenes.esImagenValida(new byte[]{0x42, 0x4D, 0x00, 0x00}));
    }

    @Test
    void rechazaTextoBinarioYVacio() {
        assertFalse(Imagenes.esImagenValida(null));
        assertFalse(Imagenes.esImagenValida(new byte[0]));
        assertFalse(Imagenes.esImagenValida(new byte[]{1, 2, 3}));
        assertFalse(Imagenes.esImagenValida(
                "hola mundo".getBytes(StandardCharsets.UTF_8)));
        assertFalse(Imagenes.esImagenValida(
                "%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8)));
    }
}
