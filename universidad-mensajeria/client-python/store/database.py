"""Store local SQLite (capa persistencia §7.2).

El esquema es el CANONICO compartido ``common-protocol/.../schema-local.sql``
(contrato §5.2): misma fuente que H2/Java y SQLite/C#. Nada de DDL propio.
Soporta resolución de archivo por cuenta (<base>_<cuenta>.db) y migración
idempotente desde el archivo legado compartido.
"""
import os
import sqlite3
from contextlib import contextmanager

# common-protocol/dto/src/main/resources/schema-local.sql, relativo a este archivo.
_ESQUEMA_REPOSITORIO = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    '..', '..', 'common-protocol', 'dto', 'src', 'main', 'resources', 'schema-local.sql'))
_ESQUEMA_INCLUIDO = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'schema-local.sql')
_ESQUEMA_CANONICO = next((ruta for ruta in (_ESQUEMA_REPOSITORIO, _ESQUEMA_INCLUIDO)
                          if os.path.isfile(ruta)), _ESQUEMA_INCLUIDO)


def expandir_ruta(db_path):
    if not db_path:
        return "mensajeria_cliente.db"
    if db_path.startswith('sqlite:///'):
        db_path = db_path[len('sqlite:///'):]
    if db_path.startswith('~/'):
        db_path = os.path.expanduser(db_path)
    return db_path


def sanitizar_cuenta(cuenta):
    if not cuenta or not str(cuenta).strip():
        raise ValueError("cuenta vacía")
    limpia = "".join(c if (c.isalnum() or c in "-_") else "_" for c in str(cuenta).strip()).strip("_")
    if not limpia:
        raise ValueError("cuenta sin caracteres válidos")
    return limpia


def ruta_para_cuenta(db_url_o_camino, cuenta):
    base = expandir_ruta(db_url_o_camino)
    if base == ":memory:" or base.startswith(":memory:"):
        return base
    limpia = sanitizar_cuenta(cuenta)
    dir_name, file_name = os.path.split(base)
    stem, ext = os.path.splitext(file_name)
    if not ext:
        ext = ".db"
    if stem.lower().endswith("_" + limpia.lower()):
        return os.path.join(dir_name, stem + ext)
    return os.path.join(dir_name, f"{stem}_{limpia}{ext}")


def _tabla_existe(conn, tabla):
    cur = conn.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (tabla,))
    return cur.fetchone() is not None


def migrar_legado_a_cuenta(ruta_legada, ruta_cuenta, cuenta, esquema_path=None):
    leg = expandir_ruta(ruta_legada)
    cta = expandir_ruta(ruta_cuenta)
    if leg == cta or leg == ":memory:" or cta == ":memory:":
        return
    if not os.path.isfile(leg):
        return

    dir_cta = os.path.dirname(cta)
    if dir_cta:
        os.makedirs(dir_cta, exist_ok=True)

    try:
        conn_leg = sqlite3.connect(leg, timeout=5)
        conn_leg.row_factory = sqlite3.Row
    except Exception:
        return

    try:
        if not _tabla_existe(conn_leg, "historial_local"):
            conn_leg.close()
            return
        filas_historial = conn_leg.execute("SELECT * FROM historial_local").fetchall()
        filas_pendientes = []
        if _tabla_existe(conn_leg, "pendientes_envio"):
            filas_pendientes = conn_leg.execute(
                "SELECT * FROM pendientes_envio WHERE origen = ?", (cuenta,)).fetchall()
        filas_usuarios = []
        if _tabla_existe(conn_leg, "cache_usuarios"):
            filas_usuarios = conn_leg.execute("SELECT * FROM cache_usuarios").fetchall()
    except Exception:
        conn_leg.close()
        return
    finally:
        conn_leg.close()

    # Abrir destino y asegurar esquema
    esquema_file = esquema_path or _ESQUEMA_CANONICO
    conn_cta = sqlite3.connect(cta, timeout=10)
    try:
        conn_cta.execute("PRAGMA journal_mode=WAL;")
        conn_cta.execute("PRAGMA busy_timeout=5000;")
        if os.path.exists(esquema_file):
            with open(esquema_file, 'r', encoding='utf-8') as f:
                conn_cta.executescript(f.read())
        # Asegurar columnas estado y archivo_id
        columnas = {row[1] for row in conn_cta.execute("PRAGMA table_info(historial_local)")}
        if "estado" not in columnas:
            conn_cta.execute("ALTER TABLE historial_local ADD COLUMN estado TEXT NOT NULL DEFAULT 'ENVIADO'")
        if "archivo_id" not in columnas:
            conn_cta.execute("ALTER TABLE historial_local ADD COLUMN archivo_id TEXT")

        # Migrar historial de forma idempotente recalculando enviado para esta cuenta
        for f in filas_historial:
            id_m = f["id_mensaje"]
            origen = f["origen"] or ""
            enviado = 1 if origen == cuenta else 0
            conn_cta.execute("""
                INSERT INTO historial_local (
                    id_mensaje, origen, destino, tipo, contenido, hash_sha256,
                    num_caracteres, num_palabras, nombre_archivo, ruta_archivo,
                    tamano_archivo, fecha_envio, enviado, descargado, estado, archivo_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id_mensaje) DO UPDATE SET
                    descargado = MAX(excluded.descargado, historial_local.descargado),
                    archivo_id = COALESCE(excluded.archivo_id, historial_local.archivo_id),
                    ruta_archivo = COALESCE(excluded.ruta_archivo, historial_local.ruta_archivo),
                    estado = CASE
                        WHEN historial_local.estado = 'LEIDO' OR excluded.estado = 'LEIDO' THEN 'LEIDO'
                        WHEN historial_local.estado = 'ENTREGADO' AND excluded.estado IN ('PENDIENTE','ENVIADO') THEN 'ENTREGADO'
                        WHEN historial_local.estado = 'ENVIADO' AND excluded.estado = 'PENDIENTE' THEN 'ENVIADO'
                        ELSE excluded.estado END
            """, (
                id_m, origen, f["destino"] or "", f["tipo"] or "MENSAJE_TEXTO",
                f["contenido"], f["hash_sha256"], f["num_caracteres"], f["num_palabras"],
                f["nombre_archivo"], f["ruta_archivo"], f["tamano_archivo"],
                f["fecha_envio"], enviado, f["descargado"] or 0,
                f["estado"] if "estado" in f.keys() and f["estado"] else "ENVIADO",
                f["archivo_id"] if "archivo_id" in f.keys() else None
            ))

        # Migrar pendientes_envio solo originados por esta cuenta
        for p in filas_pendientes:
            p_keys = p.keys()
            p_id = p["id"] if "id" in p_keys else p["id_mensaje"]
            p_fecha = p["fecha_creado"] if "fecha_creado" in p_keys else (p["fecha_envio"] if "fecha_envio" in p_keys else "")
            p_nombre = p["nombre_archivo"] if "nombre_archivo" in p_keys else ""
            p_payload = p["payload"] if "payload" in p_keys else (p["ruta_archivo"] if "ruta_archivo" in p_keys else None)
            p_intentos = p["intentos"] if "intentos" in p_keys and p["intentos"] is not None else 0
            p_error = p["ultimo_error"] if "ultimo_error" in p_keys else None

            conn_cta.execute("""
                INSERT OR IGNORE INTO pendientes_envio (
                    id, tipo, origen, destino, contenido, nombre_archivo, payload,
                    fecha_creado, intentos, ultimo_error
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, (
                p_id, p["tipo"], p["origen"], p["destino"], p["contenido"],
                p_nombre, p_payload, p_fecha, p_intentos, p_error
            ))

        # Migrar cache_usuarios
        for u in filas_usuarios:
            u_keys = u.keys()
            u_prog = u["programa"] if "programa" in u_keys else (u["carrera"] if "carrera" in u_keys else "")
            u_freg = u["fecha_registro"] if "fecha_registro" in u_keys else (u["fecha_creacion"] if "fecha_creacion" in u_keys else "")
            u_act = u["actualizado"] if "actualizado" in u_keys else (u["ultima_conexion"] if "ultima_conexion" in u_keys else "")

            conn_cta.execute("""
                INSERT OR IGNORE INTO cache_usuarios (
                    codigo, nombres, apellidos, programa, conectado, fecha_registro, actualizado
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """, (
                u["codigo"], u["nombres"], u["apellidos"], u_prog,
                u["conectado"] or 0, u_freg, u_act
            ))
        conn_cta.commit()
    finally:
        conn_cta.close()


class Database:
    def __init__(self, db_url, esquema_path=None, cuenta=None):
        self.db_url = db_url
        self.cuenta = sanitizar_cuenta(cuenta) if cuenta else None
        self.esquema_path = esquema_path or _ESQUEMA_CANONICO
        
        if self.cuenta and self.db_url != ":memory:":
            self.db_path = ruta_para_cuenta(self.db_url, self.cuenta)
            ruta_legada = expandir_ruta(self.db_url)
            migrar_legado_a_cuenta(ruta_legada, self.db_path, self.cuenta, self.esquema_path)
        else:
            self.db_path = expandir_ruta(self.db_url)

        dir_db = os.path.dirname(self.db_path)
        if dir_db and self.db_path != ":memory:":
            os.makedirs(dir_db, exist_ok=True)

        self._init_db()

    def para_cuenta(self, cuenta):
        """Retorna una instancia de Database configurada para la cuenta especificada."""
        if not cuenta:
            return self
        if self.cuenta == sanitizar_cuenta(cuenta):
            return self
        return Database(self.db_url, esquema_path=self.esquema_path, cuenta=cuenta)

    def get_connection(self):
        conn = sqlite3.connect(self.db_path, timeout=30)
        conn.row_factory = sqlite3.Row
        try:
            conn.execute("PRAGMA journal_mode=WAL;")
            conn.execute("PRAGMA busy_timeout=5000;")
        except Exception:
            pass
        return conn

    @contextmanager
    def conexion(self):
        """Conexion que SIEMPRE se cierra (el ``with`` de sqlite3 solo hace
        commit/rollback y dejaba el .db bloqueado en Windows)."""
        conn = self.get_connection()
        try:
            yield conn
            conn.commit()
        finally:
            conn.close()

    def _init_db(self):
        if not os.path.exists(self.esquema_path):
            raise RuntimeError(
                "Falta el esquema canonico: %s (§5.2: schema-local.sql compartido)"
                % self.esquema_path)
        with open(self.esquema_path, 'r', encoding='utf-8') as f:
            esquema = f.read()
        with self.conexion() as conn:
            conn.executescript(esquema)
        self._migrar_historial()

    def _migrar_historial(self):
        with self.conexion() as conn:
            conn.execute("BEGIN IMMEDIATE")
            columnas = {fila[1] for fila in conn.execute(
                "PRAGMA table_info(historial_local)")}
            if "estado" not in columnas:
                conn.execute("ALTER TABLE historial_local ADD COLUMN estado "
                             "TEXT NOT NULL DEFAULT 'ENVIADO'")
            if "archivo_id" not in columnas:
                conn.execute("ALTER TABLE historial_local ADD COLUMN archivo_id TEXT")
