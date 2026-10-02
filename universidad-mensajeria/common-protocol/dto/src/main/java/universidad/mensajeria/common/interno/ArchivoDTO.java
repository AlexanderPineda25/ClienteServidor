package universidad.mensajeria.common.interno;

/** Metadatos planos de un archivo (descargas). Lo construye AlmacenarInformacion. */
public record ArchivoDTO(
        long id,
        String nombre,
        String ruta,
        long tamano,
        String tipo,
        String mime,
        String hashSha256
) {
}
