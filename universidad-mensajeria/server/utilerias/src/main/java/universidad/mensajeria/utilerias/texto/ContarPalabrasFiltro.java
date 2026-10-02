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

/** Conteo de palabras separadas por blancos del .txt de entrada. */
public final class ContarPalabrasFiltro implements Filtro {

    @Override
    public ResultadoFiltro ejecutar(List<File> entrada, ContextoTuberia ctx) throws FiltroException {
        if (entrada.isEmpty()) {
            throw new FiltroException(nombre(), "sin archivos de entrada");
        }
        try {
            String contenido = Files.readString(entrada.get(0).toPath(), StandardCharsets.UTF_8);
            int n = contenido.isBlank() ? 0 : contenido.trim().split("\\s+").length;
            return new ResultadoFiltro(entrada, Metadatos.de("numPalabras", String.valueOf(n)));
        } catch (Exception e) {
            throw new FiltroException(nombre(), "no se pudo contar palabras", e);
        }
    }

    @Override
    public String nombre() {
        return "ContarPalabras";
    }
}
