package universidad.mensajeria.common.interno;

/**
 * Conteo de conexiones por usuario (FASE 9, §8, Informe 2): salida del
 * GROUP BY JPQL sobre registro_acciones (tipos LOGIN/CONECTADO).
 */
public record LoginConteoDTO(String codigo, long veces) {
}
