package universidad.mensajeria.server.vistaescritorio;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Previsualización de filtros de la pestaña Mensajes: núcleo puro
 * (sin toolkit JavaFX) sobre una imagen generada en memoria.
 */
class VistaEscritorioFiltrosTest {

    private static BufferedImage imagenPrueba() {
        BufferedImage imagen = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                imagen.setRGB(x, y, ((x * 32) << 16) | ((y * 32) << 8) | 128);
            }
        }
        return imagen;
    }

    @Test
    void cadaFiltroDevuelveImagenNuevaDelTamanoEsperado() {
        BufferedImage base = imagenPrueba();
        assertEquals(8, VistaEscritorio.aplicarFiltroVista(base, "Original").getWidth());
        assertEquals(8, VistaEscritorio.aplicarFiltroVista(base, "Grises").getHeight());
        assertEquals(8, VistaEscritorio.aplicarFiltroVista(base, "Sepia").getWidth());
        assertEquals(8, VistaEscritorio.aplicarFiltroVista(base, "Giro 180").getHeight());
        assertEquals(8, VistaEscritorio.aplicarFiltroVista(base, "Brillo x1.25").getWidth());
        BufferedImage reducida = VistaEscritorio.aplicarFiltroVista(base, "Reducida 50%");
        assertEquals(4, reducida.getWidth());
        assertEquals(4, reducida.getHeight());
    }

    @Test
    void losFiltrosTransformanPixelesSinTocarLaBase() {
        BufferedImage base = imagenPrueba();
        int antes = base.getRGB(1, 1);
        BufferedImage grises = VistaEscritorio.aplicarFiltroVista(base, "Grises");
        assertNotSame(base, grises);
        int rgb = grises.getRGB(1, 1);
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        assertEquals(r, g, "grises: canales iguales");
        assertEquals(g, b, "grises: canales iguales");
        assertEquals(antes, base.getRGB(1, 1), "la base no se muta");
    }

    @Test
    void opcionDesconocidaDevuelveCopia() {
        BufferedImage base = imagenPrueba();
        BufferedImage copia = VistaEscritorio.aplicarFiltroVista(base, "Inexistente");
        assertNotSame(base, copia);
        assertEquals(base.getRGB(3, 3), copia.getRGB(3, 3));
    }

    @Test
    void baseVaciaLanzaErrorClaro() {
        assertThrows(IllegalArgumentException.class,
                () -> VistaEscritorio.aplicarFiltroVista(null, "Grises"));
        assertTrue(VistaEscritorio.aplicarFiltroVista(imagenPrueba(), null).getWidth() > 0,
                "opcion nula equivale a Original");
    }
}
