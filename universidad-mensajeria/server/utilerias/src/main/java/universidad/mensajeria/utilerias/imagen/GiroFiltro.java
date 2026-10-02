package universidad.mensajeria.utilerias.imagen;

import java.awt.image.BufferedImage;

/** Giro (grados configurables, por defecto 180) → {@code 03_giro180.png}. */
public final class GiroFiltro extends FiltroVisualBase {

    private final int grados;

    public GiroFiltro() {
        this(180);
    }

    public GiroFiltro(int grados) {
        super("03_giro180.png");
        this.grados = grados;
    }

    @Override
    protected BufferedImage transformar(BufferedImage entrada) {
        return Imagenes.girar(entrada, grados);
    }

    @Override
    public String nombre() {
        return "Giro" + grados;
    }
}
