-- ============================================================
-- ESQUEMA LOCAL MULTILENGUAJE (contrato neutro)
-- Fuente: common-protocol/dto/src/main/resources/schema-local.sql
-- Dialecto neutro: solo TEXT / INTEGER / REAL / BLOB (sin secuencias
-- ni tipos de motor). Ejecutable tal cual en H2 y SQLite (y cualquier
-- motor que soporte estos tipos). Ids clave natural o UUID en TEXT
-- para no depender de AUTOINCREMENT.
-- Las queries canónicas estan en HISTORIAL_LOCAL.md.
-- ============================================================

CREATE TABLE IF NOT EXISTS historial_local (
    id_mensaje     TEXT PRIMARY KEY,          -- id del mensaje en el servidor (uuid)
    origen         TEXT NOT NULL,             -- codigo emisor
    destino        TEXT NOT NULL,             -- codigo receptor
    tipo           TEXT NOT NULL,             -- MENSAJE_TEXTO | MENSAJE_IMAGEN
    contenido      TEXT,
    hash_sha256    TEXT,
    num_caracteres INTEGER,
    num_palabras   INTEGER,
    nombre_archivo TEXT,
    archivo_id     TEXT,                      -- id remoto para re-descargar imagen tras reiniciar
    ruta_archivo   TEXT,                      -- copia local del archivo descargado
    tamano_archivo INTEGER,
    fecha_envio    TEXT NOT NULL,             -- ISO-8601 UTC
    enviado        INTEGER NOT NULL DEFAULT 0, -- 1 si lo envio este cliente
    descargado     INTEGER NOT NULL DEFAULT 0,
    estado         TEXT NOT NULL DEFAULT 'ENVIADO' -- PENDIENTE | ENVIADO | ENTREGADO | LEIDO
);

CREATE INDEX IF NOT EXISTS idx_hist_conv
    ON historial_local (origen, destino, fecha_envio);

CREATE TABLE IF NOT EXISTS cache_usuarios (
    codigo         TEXT PRIMARY KEY,
    nombres        TEXT NOT NULL,
    apellidos      TEXT NOT NULL,
    programa       TEXT,
    conectado      INTEGER NOT NULL DEFAULT 0,
    fecha_registro TEXT,
    actualizado    TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS pendientes_envio (
    id             TEXT PRIMARY KEY,          -- uuid generado por el cliente
    tipo           TEXT NOT NULL,             -- MENSAJE_TEXTO | MENSAJE_IMAGEN | MENSAJE_LEIDO
    origen         TEXT NOT NULL,
    destino        TEXT NOT NULL,
    contenido      TEXT,
    nombre_archivo TEXT,
    payload        BLOB,                      -- bytes de la imagen si aplica
    fecha_creado   TEXT NOT NULL,
    intentos       INTEGER NOT NULL DEFAULT 0,
    ultimo_error   TEXT
);

CREATE INDEX IF NOT EXISTS idx_pend_destino
    ON pendientes_envio (destino, fecha_creado);
