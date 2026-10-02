package universidad.mensajeria.common.interno;

/**
 * Fechas de un usuario en BD (FASE 9, §8): registro + ultima conexion
 * (columnas fecha_registro y fecha_ultima_conexion de la tabla usuarios).
 */
public record FechasUsuarioDTO(String codigo, String fechaRegistro,
                               String fechaUltimaConexion) {
}
