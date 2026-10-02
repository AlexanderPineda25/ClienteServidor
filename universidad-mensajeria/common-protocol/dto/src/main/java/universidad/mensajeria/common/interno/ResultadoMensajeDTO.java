package universidad.mensajeria.common.interno;

import java.util.List;

/**
 * Resultado de procesar un mensaje (PLAN2 §2.5 Regla 5).
 * Mensajes.procesarX(...) lo devuelve en un CompletableFuture; la Fachada
 * encadena el envio al terminar.
 */
public record ResultadoMensajeDTO(
        Long mensajeId,
        String hashSha256,
        Integer numCaracteres,
        Integer numPalabras,
        List<String> rutasDerivadas,
        Long archivoId,
        List<EventoEtapa> traza
) {
}
