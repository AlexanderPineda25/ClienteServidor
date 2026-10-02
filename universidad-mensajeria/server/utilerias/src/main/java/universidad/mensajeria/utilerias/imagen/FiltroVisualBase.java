package universidad.mensajeria.utilerias.imagen;

import universidad.mensajeria.utilerias.ContextoTuberia;
import universidad.mensajeria.utilerias.Filtro;
import universidad.mensajeria.utilerias.FiltroException;
import universidad.mensajeria.utilerias.ResultadoFiltro;
import universidad.mensajeria.utilerias.ResultadoFiltro.Metadatos;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.List;

/**
 * Base visual: transforma la ultima imagen, escribe {@code NN_nombre.png} en
 * el directorio de trabajo y la devuelve como unica salida (cadena).
 */
abstract sealed class FiltroVisualBase implements Filtro
        permits EscalaGrisesFiltro, SepiaFiltro, GiroFiltro, BrilloFiltro, ReduccionFiltro {

    private final String archivo;

    protected FiltroVisualBase(String archivo) {
        this.archivo = archivo;
    }

    protected abstract BufferedImage transformar(BufferedImage entrada);

    @Override
    public ResultadoFiltro ejecutar(List<File> entrada, ContextoTuberia ctx) throws FiltroException {
        if (entrada.isEmpty()) {
            throw new FiltroException(nombre(), "sin archivos de entrada");
        }
        try {
            BufferedImage transformada = transformar(Imagenes.leer(entrada.get(entrada.size() - 1).toPath()));
            Path salida = Imagenes.escribir(transformada, ctx.directorioTrabajo(), archivo);
            return new ResultadoFiltro(List.of(salida.toFile()), Metadatos.vacios());
        } catch (Exception e) {
            throw new FiltroException(nombre(), "no se pudo aplicar el filtro", e);
        }
    }
}
