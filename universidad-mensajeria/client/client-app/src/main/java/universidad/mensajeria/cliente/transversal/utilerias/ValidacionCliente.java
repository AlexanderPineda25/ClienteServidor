package universidad.mensajeria.cliente.transversal.utilerias;

/**
 * Validaciones de entrada del cliente (puras, sin red ni UI: testeables).
 * Mismas reglas que el servidor exige (TextoCommand/ImagenCommand/ArchivoCommand).
 */
public final class ValidacionCliente {

    private ValidacionCliente() {
    }

    public static void codigo(String codigo) {
        if (codigo == null || codigo.isBlank()) {
            throw new IllegalArgumentException("el codigo es obligatorio");
        }
    }

    public static void contrasena(String contrasena) {
        if (contrasena == null || contrasena.length() < 6) {
            throw new IllegalArgumentException("la contrasena debe tener al menos 6 caracteres");
        }
    }

    public static void contenido(String contenido) {
        if (contenido == null || contenido.isBlank()) {
            throw new IllegalArgumentException("el contenido no puede estar vacio");
        }
    }

    public static void destinatario(String destinatario) {
        if (destinatario == null || destinatario.isBlank()) {
            throw new IllegalArgumentException("elige un destinatario");
        }
    }

    public static void imagen(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("la imagen esta vacia");
        }
    }
}
