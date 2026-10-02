package universidad.mensajeria.common.interno;

/**
 * Evento de etapa de tuberia (PLAN2 §2.5 Regla 5): etapa, ms, hilo y ruta de
 * salida. Lo emite cada filtro a su oyente; la Fachada lo reenvia a
 * LogDeEventos. Mensajes no conoce a LogDeEventos.
 */
public record EventoEtapa(String etapa, long ms, String hilo, String rutaSalida) {
}
