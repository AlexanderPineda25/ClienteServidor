package universidad.mensajeria.common.interno;

/** Resumen de usuario compartido por fachada y vistas (DTO-Record). */
public record UsuarioResumen(
        String codigo,
        String nombres,
        String apellidos,
        String programa,
        boolean conectado) {
}
