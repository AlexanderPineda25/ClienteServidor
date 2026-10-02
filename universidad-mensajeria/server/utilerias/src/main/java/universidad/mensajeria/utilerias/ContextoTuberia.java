package universidad.mensajeria.utilerias;

import universidad.mensajeria.common.interno.EventoEtapa;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Contexto inmutable de ejecucion (PLAN2 §3.1): directorio temporal donde los
 * filtros escriben y oyente de etapas (lo registra la Fachada y lo reenvia a
 * LogDeEventos; Mensajes no conoce a LogDeEventos).
 */
public record ContextoTuberia(Path directorioTrabajo, Consumer<EventoEtapa> listener) {

    public static ContextoTuberia sinOyente(Path directorioTrabajo) {
        return new ContextoTuberia(directorioTrabajo, e -> {
        });
    }
}
