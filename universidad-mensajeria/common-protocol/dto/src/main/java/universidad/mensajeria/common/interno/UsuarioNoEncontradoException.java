package universidad.mensajeria.common.interno;

/** El codigo no corresponde a ningun usuario registrado. Excepcion de dominio. */
public class UsuarioNoEncontradoException extends RuntimeException {

    public UsuarioNoEncontradoException(String codigo) {
        super("Usuario no encontrado: " + codigo);
    }
}
