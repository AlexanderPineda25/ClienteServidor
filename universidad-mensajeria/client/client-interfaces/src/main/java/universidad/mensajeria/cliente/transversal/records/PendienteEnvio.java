package universidad.mensajeria.cliente.transversal.records;

/** Fila de pendientes_envio (§5.2): mensajes creados sin red para reintentar. */
public record PendienteEnvio(
        String id,
        String tipo,
        String origen,
        String destino,
        String contenido,
        String nombreArchivo,
        byte[] payload,
        String fechaCreado,
        int intentos,
        String ultimoError) {
}
