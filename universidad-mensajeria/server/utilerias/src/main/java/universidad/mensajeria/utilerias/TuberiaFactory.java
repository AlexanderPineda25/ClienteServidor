package universidad.mensajeria.utilerias;

import universidad.mensajeria.utilerias.archivo.Sha256ArchivoFiltro;
import universidad.mensajeria.utilerias.imagen.BrilloFiltro;
import universidad.mensajeria.utilerias.imagen.EscalaGrisesFiltro;
import universidad.mensajeria.utilerias.imagen.GiroFiltro;
import universidad.mensajeria.utilerias.imagen.ReduccionFiltro;
import universidad.mensajeria.utilerias.imagen.SepiaFiltro;
import universidad.mensajeria.utilerias.imagen.Sha256ImagenFiltro;
import universidad.mensajeria.utilerias.texto.ContarCaracteresFiltro;
import universidad.mensajeria.utilerias.texto.ContarPalabrasFiltro;
import universidad.mensajeria.utilerias.texto.Sha256Filtro;

/**
 * Fabrica de tuberias (PLAN2 §3.1): texto, imagen y archivo generico.
 * Parametros con defaults de server.properties (filtros.giro.grados=180,
 * filtros.brillo.factor=1.25, filtros.reduccion.factor=0.5).
 */
public final class TuberiaFactory implements InterfazTuberiaFactory {

    private final int giroGrados;
    private final double brilloFactor;
    private final double reduccionFactor;

    public TuberiaFactory() {
        this(180, 1.25, 0.5);
    }

    public TuberiaFactory(int giroGrados, double brilloFactor, double reduccionFactor) {
        this.giroGrados = giroGrados;
        this.brilloFactor = brilloFactor;
        this.reduccionFactor = reduccionFactor;
    }

    /** SHA256 → ContarCaracteres → ContarPalabras. */
    @Override
    public Tuberia texto() {
        return new Tuberia()
                .agregar(new Sha256Filtro())
                .agregar(new ContarCaracteresFiltro())
                .agregar(new ContarPalabrasFiltro());
    }

    /** SHA256 → Grises → Sepia → Giro → Brillo → Reduccion (secuencial). */
    @Override
    public Tuberia imagen() {
        return new Tuberia()
                .agregar(new Sha256ImagenFiltro())
                .agregar(new EscalaGrisesFiltro())
                .agregar(new SepiaFiltro())
                .agregar(new GiroFiltro(giroGrados))
                .agregar(new BrilloFiltro(brilloFactor))
                .agregar(new ReduccionFiltro(reduccionFactor));
    }

    /** Archivo generico (PDF, documentos): solo SHA-256, sin transformar. */
    @Override
    public Tuberia archivo() {
        return new Tuberia()
                .agregar(new Sha256ArchivoFiltro());
    }
}
