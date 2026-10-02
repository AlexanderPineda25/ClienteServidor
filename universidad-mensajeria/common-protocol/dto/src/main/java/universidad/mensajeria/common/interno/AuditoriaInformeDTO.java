package universidad.mensajeria.common.interno;

/**
 * Fila del Informe 4 (FASE 9, §8): bitacora de auditoria (tabla
 * registro_acciones, la misma fuente que alimenta logs/server.log).
 */
public record AuditoriaInformeDTO(long id, String tipo, String usuario, String descripcion,
                                  String fecha, String ip, String nombres, String apellidos) {
    public AuditoriaInformeDTO(long id, String tipo, String usuario, String descripcion,
                               String fecha, String ip) {
        this(id, tipo, usuario, descripcion, fecha, ip, null, null);
    }
}
