-- ============================================================
-- ESQUEMA LOCAL MULTILENGUAJE (contrato neutro)
-- ============================================================
CREATE TABLE IF NOT EXISTS historial_local (
    id_mensaje     TEXT PRIMARY KEY,
    origen         TEXT NOT NULL,
    destino        TEXT NOT NULL,
    tipo           TEXT NOT NULL,
    contenido      TEXT,
    hash_sha256    TEXT,
    num_caracteres INTEGER,
    num_palabras   INTEGER,
    nombre_archivo TEXT,
    archivo_id     TEXT,
    ruta_archivo   TEXT,
    tamano_archivo INTEGER,
    fecha_envio    TEXT NOT NULL,
    enviado        INTEGER NOT NULL DEFAULT 0,
    descargado     INTEGER NOT NULL DEFAULT 0,
    estado         TEXT NOT NULL DEFAULT 'ENVIADO'
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
    id             TEXT PRIMARY KEY,
    tipo           TEXT NOT NULL,
    origen         TEXT NOT NULL,
    destino        TEXT NOT NULL,
    contenido      TEXT,
    nombre_archivo TEXT,
    payload        BLOB,
    fecha_creado   TEXT NOT NULL,
    intentos       INTEGER NOT NULL DEFAULT 0,
    ultimo_error   TEXT
);

CREATE INDEX IF NOT EXISTS idx_pend_destino
    ON pendientes_envio (destino, fecha_creado);
