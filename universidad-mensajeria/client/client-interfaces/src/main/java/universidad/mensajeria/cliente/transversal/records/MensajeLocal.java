package universidad.mensajeria.cliente.transversal.records;

/**
 * Fila de historial_local (§5.2): copia local del mensaje enviado o recibido.
 * destino "*" marca un broadcast propio (sin destinatario único).
 */
public record MensajeLocal(
        String idMensaje,
        String origen,
        String destino,
        String tipo,
        String contenido,
        String hashSha256,
        Integer numCaracteres,
        Integer numPalabras,
        String nombreArchivo,
        String rutaArchivo,
        Long tamanoArchivo,
        String fechaEnvio,
        boolean enviado,
        boolean descargado,
        String estado,
        String archivoId) {

    /** Compatibilidad con filas anteriores sin metadatos de archivo. */
    public MensajeLocal(
            String idMensaje,
            String origen,
            String destino,
            String tipo,
            String contenido,
            String hashSha256,
            Integer numCaracteres,
            Integer numPalabras,
            String nombreArchivo,
            String rutaArchivo,
            Long tamanoArchivo,
            String fechaEnvio,
            boolean enviado,
            boolean descargado,
            String estado) {
        this(idMensaje, origen, destino, tipo, contenido, hashSha256, numCaracteres,
                numPalabras, nombreArchivo, rutaArchivo, tamanoArchivo, fechaEnvio,
                enviado, descargado, estado, null);
    }

    /** Compatibilidad: sin estado explícito asume ENVIADO. */
    public MensajeLocal(
            String idMensaje,
            String origen,
            String destino,
            String tipo,
            String contenido,
            String hashSha256,
            Integer numCaracteres,
            Integer numPalabras,
            String nombreArchivo,
            String rutaArchivo,
            Long tamanoArchivo,
            String fechaEnvio,
            boolean enviado,
            boolean descargado) {
        this(idMensaje, origen, destino, tipo, contenido, hashSha256, numCaracteres,
                numPalabras, nombreArchivo, rutaArchivo, tamanoArchivo, fechaEnvio,
                enviado, descargado, "ENVIADO", null);
    }
}
