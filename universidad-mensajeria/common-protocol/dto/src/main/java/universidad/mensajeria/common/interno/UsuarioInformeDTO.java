package universidad.mensajeria.common.interno;

/**
 * Fila del Informe 1 (FASE 9, §8): directorio de usuarios registrados.
 * La Fachada combina el directorio en memoria (Usuarios) con las fechas de
 * BD (AlmacenarInformacion) y el estado en caliente (ConexionesClientes).
 */
public record UsuarioInformeDTO(String codigo, String nombres, String apellidos,
                                String programa, String fechaRegistro,
                                String fechaUltimaConexion, boolean conectado) {

    public UsuarioInformeDTO conConectado(boolean valor) {
        return new UsuarioInformeDTO(codigo, nombres, apellidos, programa,
                fechaRegistro, fechaUltimaConexion, valor);
    }
}
