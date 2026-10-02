package universidad.mensajeria.common.interno;

/**
 * Limites operativos (PLAN2 §2.5 Regla 6): `Servicios` es logica pura y los
 * recibe de `Fabrica` desde server.properties. Sin acceso a config ni a BD.
 */
public record LimitesDTO(
        int maxConexiones,
        int maxConexionesPorUsuario,
        long maxTamanoArchivo
) {
}
