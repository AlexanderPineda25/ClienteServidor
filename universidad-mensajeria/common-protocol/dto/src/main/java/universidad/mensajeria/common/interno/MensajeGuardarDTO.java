package universidad.mensajeria.common.interno;

/** Comando plano para guardar un mensaje sin exponer entidades JPA. */
public record MensajeGuardarDTO(String remitente, String destinatario, String tipo,
                                String contenido, String hashSha256, Integer numCaracteres,
                                Integer numPalabras, Long archivoId, String etapasFiltrado,
                                String ipRemitente) {
}
