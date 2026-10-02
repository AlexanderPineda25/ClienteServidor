package universidad.mensajeria.common.interno;

/** Datos planos necesarios para registrar un archivo desde el puerto de persistencia. */
public record ArchivoGuardarDTO(String nombre, String ruta, long tamano, String tipo,
                                String mime, String hashSha256, String codigoPropietario) {
}
