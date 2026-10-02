package universidad.mensajeria.cliente.componentes.autenticacion;

/** Sesión del cliente: autenticación y ciclo de vida (sin registro desde cliente). */
public interface InterfazAutenticacion {

    /** true si el servidor aceptó las credenciales (guarda el código). */
    boolean login(String codigo, String contrasena) throws Exception;

    void logout() throws Exception;

    void olvidarSesion();

    /** Código logueado o null. */
    String codigoActual();

    boolean sesionAbierta();
}
