package universidad.mensajeria.utilerias.imagen;

import java.awt.image.BufferedImage;

/** Grises → {@code 01_grises.png}. */
public final class EscalaGrisesFiltro extends FiltroVisualBase {

    public EscalaGrisesFiltro() {
        super("01_grises.png");
    }

    @Override
    protected BufferedImage transformar(BufferedImage entrada) {
        return Imagenes.aGrises(entrada);
    }

    @Override
    public String nombre() {
        return "EscalaGrises";
    }
}
