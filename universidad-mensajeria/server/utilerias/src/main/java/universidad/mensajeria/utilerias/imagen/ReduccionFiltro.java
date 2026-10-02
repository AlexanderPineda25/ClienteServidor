package universidad.mensajeria.utilerias.imagen;

import java.awt.image.BufferedImage;

/** Reduccion (factor configurable) → {@code 05_reduccion.png}. */
public final class ReduccionFiltro extends FiltroVisualBase {

    private final double factor;

    public ReduccionFiltro() {
        this(0.5);
    }

    public ReduccionFiltro(double factor) {
        super("05_reduccion.png");
        this.factor = factor;
    }

    @Override
    protected BufferedImage transformar(BufferedImage entrada) {
        return Imagenes.reducir(entrada, factor);
    }

    @Override
    public String nombre() {
        return "Reduccion";
    }
}
