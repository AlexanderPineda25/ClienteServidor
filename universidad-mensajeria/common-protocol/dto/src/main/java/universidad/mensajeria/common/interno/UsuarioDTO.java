package universidad.mensajeria.common.interno;

/**
 * Usuario del directorio (PLAN2 §2.5 Regla 6): datos en memoria en `Usuarios`;
 * el hash viaja opaco (la verificacion la aporta un callback).
 */
public record UsuarioDTO(
        String codigo,
        String nombres,
        String apellidos,
        String programa,
        String hashContrasena,
        boolean activo
) {
}
