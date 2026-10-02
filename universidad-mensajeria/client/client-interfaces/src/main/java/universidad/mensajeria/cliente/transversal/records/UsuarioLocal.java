package universidad.mensajeria.cliente.transversal.records;

/** Fila de cache_usuarios (§5.2): directorio local para operar offline. */
public record UsuarioLocal(
        String codigo,
        String nombres,
        String apellidos,
        String programa,
        boolean conectado,
        String fechaRegistro,
        String actualizado) {
}
