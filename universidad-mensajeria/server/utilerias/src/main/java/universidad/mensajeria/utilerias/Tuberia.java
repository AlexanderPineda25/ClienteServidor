package universidad.mensajeria.utilerias;

import universidad.mensajeria.common.interno.EventoEtapa;
import universidad.mensajeria.utilerias.ResultadoFiltro.Metadatos;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Tuberia secuencial (Composite, PLAN2 §3): la salida del filtro i es la
 * entrada del i+1; fusiona los Metadatos de cada resultado. Un fallo detiene
 * la cadena sin resultado parcial (§3.4). Cada etapa emite su EventoEtapa.
 */
public final class Tuberia {

    private final List<Filtro> etapas = new ArrayList<>();

    public Tuberia agregar(Filtro filtro) {
        etapas.add(filtro);
        return this;
    }

    public ResultadoTuberia ejecutar(List<File> entrada, ContextoTuberia ctx) throws FiltroException {
        List<File> actual = List.copyOf(entrada);
        Metadatos acumulados = Metadatos.vacios();
        List<EventoEtapa> traza = new ArrayList<>();
        for (Filtro etapa : etapas) {
            long inicio = System.nanoTime();
            ResultadoFiltro resultado = etapa.ejecutar(actual, ctx);
            long ms = (System.nanoTime() - inicio) / 1_000_000;
            actual = resultado.archivos();
            acumulados = acumulados.fusionar(resultado.metadatos());
            String salida = actual.isEmpty() ? "" : actual.get(actual.size() - 1).getAbsolutePath();
            EventoEtapa evento = new EventoEtapa(etapa.nombre(), ms,
                    Thread.currentThread().getName(), salida);
            traza.add(evento);
            ctx.listener().accept(evento);
        }
        return new ResultadoTuberia(actual, acumulados, List.copyOf(traza));
    }

    public int numeroEtapas() {
        return etapas.size();
    }

    /** Salida final + metadatos fusionados + traza de etapas. */
    public record ResultadoTuberia(List<File> archivos, Metadatos metadatos,
                                   List<EventoEtapa> traza) {
    }
}
