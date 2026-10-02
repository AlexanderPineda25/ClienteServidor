package universidad.mensajeria.common.interno;

/** Estado observable del pool de trabajadores TCP. */
public record EstadoPool(int ocupados, int disponibles, int total) {
}
