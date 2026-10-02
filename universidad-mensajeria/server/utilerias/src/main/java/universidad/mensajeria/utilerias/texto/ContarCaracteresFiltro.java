package universidad.mensajeria.utilerias.texto;

import universidad.mensajeria.utilerias.ContextoTuberia;
import universidad.mensajeria.utilerias.Filtro;
import universidad.mensajeria.utilerias.FiltroException;
import universidad.mensajeria.utilerias.ResultadoFiltro;
import universidad.mensajeria.utilerias.ResultadoFiltro.Metadatos;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Conteo de caracteres (code points) del .txt de entrada. */
public final class ContarCaracteresFiltro implements Filtro {

    @Override
    public ResultadoFiltro ejecutar(List<File> entrada, ContextoTuberia ctx) throws FiltroException {
        if (entrada.isEmpty()) {
            throw new FiltroException(nombre(), "sin archivos de entrada");
        }
        try {
            String contenido = Files.readString(entrada.get(0).toPath(), StandardCharsets.UTF_8);
            int n = contenido.codePointCount(0, contenido.length());
            return new ResultadoFiltro(entrada, Metadatos.de("numCaracteres", String.valueOf(n)));
        } catch (Exception e) {
            throw new FiltroException(nombre(), "no se pudo contar caracteres", e);
        }
    }

    @Override
    public String nombre() {
        return "ContarCaracteres";
    }
}
