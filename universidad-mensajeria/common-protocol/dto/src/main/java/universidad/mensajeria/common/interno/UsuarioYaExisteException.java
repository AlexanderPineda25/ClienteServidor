package universidad.mensajeria.common.interno;

/** El codigo solicitado ya esta registrado (REGISTRO). Excepcion de dominio. */
public class UsuarioYaExisteException extends RuntimeException {

    public UsuarioYaExisteException(String codigo) {
        super("El codigo ya esta registrado: " + codigo);
    }
}
