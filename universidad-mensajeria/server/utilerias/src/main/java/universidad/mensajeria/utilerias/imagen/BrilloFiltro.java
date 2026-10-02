package universidad.mensajeria.utilerias.imagen;

import java.awt.image.BufferedImage;

/** Brillo (factor configurable) → {@code 04_brillo.png}. */
public final class BrilloFiltro extends FiltroVisualBase {

    private final double factor;

    public BrilloFiltro() {
        this(1.25);
    }

    public BrilloFiltro(double factor) {
        super("04_brillo.png");
        this.factor = factor;
    }

    @Override
    protected BufferedImage transformar(BufferedImage entrada) {
        return Imagenes.ajustarBrillo(entrada, factor);
    }

    @Override
    public String nombre() {
        return "Brillo";
    }
}
