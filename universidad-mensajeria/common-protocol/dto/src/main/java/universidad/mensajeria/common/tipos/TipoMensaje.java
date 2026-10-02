package universidad.mensajeria.common.tipos;

/**
 * Catalogo de tipos de mensaje del wire protocol (PROTOCOLO.md 4).
 * Agregar un tipo nuevo aqui + su command handler en el servidor es suficiente (OCP).
 */
public enum TipoMensaje {
    // Autenticacion / sesion
    LOGIN,
    LOGIN_RESPUESTA,
    REGISTRO,
    REGISTRO_RESPUESTA,
    LOGOUT,
    CLOSE_NOTICE,
    KICK,

    // Mensajeria
    MENSAJE_TEXTO,
    MENSAJE_IMAGEN,
    MENSAJE_ARCHIVO,
    // Archivos fragmentados (>1 MiB, tope 50 MB): INICIO anuncia,
    // PARTE lleva un bloque base64 ≤1 MiB (secuencial), FIN cierra con sha256.
    ARCHIVO_INICIO,
    ARCHIVO_PARTE,
    ARCHIVO_FIN,
    IMAGE_FILTERED_RESULT,
    BROADCAST,
    // Acuses de entrega/lectura + presencia (UX Java)
    MENSAJE_ENTREGADO,
    MENSAJE_LEIDO,
    PRESENCIA,

    // Consultas
    LISTAR_CONECTADOS,
    LISTAR_CONECTADOS_RESPUESTA,
    LISTAR_USUARIOS,
    LISTAR_USUARIOS_RESPUESTA,
    HISTORIAL_REQ,
    HISTORIAL_PAGE,
    DESCARGAR_ARCHIVO,
    DESCARGAR_ARCHIVO_RESPUESTA,

    // Transversal
    SYNC_LOGIN,
    ACK,
    ERROR
}
