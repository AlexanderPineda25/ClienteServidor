package universidad.mensajeria.utilerias;

import java.io.File;
import java.util.List;

/**
 * Filtro sin estado (PLAN2 §3.1): todo lo que produce sale en su resultado.
 * Strategy independiente: se prueba aislado y es sustituible (LSP).
 */
public interface Filtro {

    ResultadoFiltro ejecutar(List<File> entrada, ContextoTuberia ctx) throws FiltroException;

    /** Nombre de la etapa, para logs y trazabilidad. */
    String nombre();
}
