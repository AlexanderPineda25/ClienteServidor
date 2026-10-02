package universidad.mensajeria.common.interno;

/** Tipos de evento auditados (tabla registro_acciones, §5.1 + §8 informe 4). */
public enum TipoAccion {
    CONECTADO,
    DESCONECTADO,
    LOGIN,
    LOGIN_FALLIDO,
    REGISTRO,
    MENSAJE_TEXTO,
    MENSAJE_IMAGEN,
    MENSAJE_ARCHIVO,
    ETAPA_FILTRADO,
    USUARIO_REGISTRADO,
    BROADCAST,
    HISTORIAL,
    DESCARGA,
    CIERRE_CONEXION,
    LIMITE_ALCANZADO,
    ERROR
}
