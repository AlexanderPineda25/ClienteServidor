package universidad.mensajeria.utilerias.imagen;

import java.awt.image.BufferedImage;

/** Sepia → {@code 02_sepia.png}. */
public final class SepiaFiltro extends FiltroVisualBase {

    public SepiaFiltro() {
        super("02_sepia.png");
    }

    @Override
    protected BufferedImage transformar(BufferedImage entrada) {
        return Imagenes.aSepia(entrada);
    }

    @Override
    public String nombre() {
        return "Sepia";
    }
}
