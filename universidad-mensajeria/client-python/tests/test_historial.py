"""DAO local sobre SQLite con el esquema CANONICO (fixture compartida §13.9)."""
import os
import sqlite3
import tempfile
import unittest

from componentes.historial_local import HistorialLocal
from store.database import Database

ESQUEMA_CANONICO = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    '..', '..', 'common-protocol', 'dto', 'src', 'main', 'resources', 'schema-local.sql'))

# Fixture compartida: mismas filas que verifican H2 (Java) y SQLite (C#).
FIXTURE_MENSAJES = [
    ("m1", "A", "B", "MENSAJE_TEXTO", "hola", "h1", 4, 1, None, None, None,
     "2026-01-01T10:00:00", 1, 1),
    ("m2", "B", "A", "MENSAJE_TEXTO", "que tal", "h2", 7, 2, None, None, None,
     "2026-01-01T10:05:00", 0, 1),
    ("m3", "A", "C", "MENSAJE_IMAGEN", "[Imagen]", "h3", None, None, "f.png",
     None, 12, "2026-01-01T10:10:00", 1, 0),
]
FIXTURE_USUARIOS = [
    ("A", "Ana", "Lopez", "Sistemas", 1, "2026-01-01", "2026-01-02T10:00:00"),
    ("B", "Luis", "Garcia", "Sistemas", 0, "2026-01-01", "2026-01-02T10:00:00"),
]


class TestHistorial(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.NamedTemporaryFile(suffix=".db", delete=False)
        tmp.close()
        self.addCleanup(os.unlink, tmp.name)
        self.db = Database("sqlite:///" + tmp.name, esquema_path=ESQUEMA_CANONICO)
        self.h = HistorialLocal(self.db)
        for fila in FIXTURE_MENSAJES:
            (mid, ori, des, tip, con, hsh, nca, npa, nar, rar, tar, fen, env, des_) = fila
            self.h.guardar_mensaje(mid, ori, des, tip, con, fen, hsh, nca or 0,
                                   npa or 0, nar or "", rar or "", tar or 0,
                                   env, des_)
        for u in FIXTURE_USUARIOS:
            self.h.actualizar_cache_usuario(*u)

    def test_pagina_ambos_sentidos_reciente_primero(self):
        pagina = self.h.obtener_conversacion("A", "B")
        self.assertEqual([m["id_mensaje"] for m in pagina], ["m2", "m1"])
        self.assertEqual(self.h.contar_conversacion("A", "B"), 2)
        self.assertEqual(self.h.contar_conversacion("A", "C"), 1)

    def test_upsert_sobreescribe_mismo_id(self):
        self.h.guardar_mensaje("m1", "A", "B", "MENSAJE_TEXTO", "editado",
                               "2026-01-01T10:00:00")
        pagina = self.h.obtener_conversacion("A", "B")
        self.assertEqual(len(pagina), 2)
        self.assertEqual(pagina[1]["contenido"], "editado")

    def test_marcar_descargado(self):
        self.h.marcar_descargado("m3", "/tmp/f.png")
        fila = self.h.obtener_conversacion("A", "C")[0]
        self.assertEqual(fila["descargado"], 1)
        self.assertEqual(fila["ruta_archivo"], "/tmp/f.png")

    def test_estado_monotonico_y_archivo_id(self):
        self.h.guardar_mensaje("estado-1", "A", "B", "MENSAJE_IMAGEN", "[Imagen]",
                               "2026-01-01T10:00:00", archivo_id="archivo-1")
        self.h.marcar_estado("estado-1", "ENTREGADO")
        self.h.marcar_estado("estado-1", "PENDIENTE")
        self.h.marcar_estado("estado-1", "LEIDO")
        fila = self.h.obtener_por_id("estado-1")
        self.assertEqual(fila["estado"], "LEIDO")
        self.assertEqual(fila["archivo_id"], "archivo-1")

    def test_pagina_no_carga_contenido_de_imagen(self):
        self.h.guardar_mensaje("imagen-base64", "A", "B", "MENSAJE_IMAGEN",
                               "aGVsbG8=", "2026-01-01T10:00:00",
                               nombre_archivo="foto.png")
        pagina = self.h.obtener_conversacion("A", "B")
        imagen = next(fila for fila in pagina if fila["id_mensaje"] == "imagen-base64")
        self.assertIsNone(imagen["contenido"])
        self.assertEqual(self.h.obtener_por_id("imagen-base64")["contenido"], "aGVsbG8=")

    def test_migracion_aditiva_conserva_historial_anterior(self):
        tmp = tempfile.NamedTemporaryFile(suffix=".db", delete=False)
        tmp.close()
        self.addCleanup(os.unlink, tmp.name)
        conn = sqlite3.connect(tmp.name)
        conn.execute("""
            CREATE TABLE historial_local (
                id_mensaje TEXT PRIMARY KEY, origen TEXT NOT NULL, destino TEXT NOT NULL,
                tipo TEXT NOT NULL, contenido TEXT, hash_sha256 TEXT,
                num_caracteres INTEGER, num_palabras INTEGER, nombre_archivo TEXT,
                ruta_archivo TEXT, tamano_archivo INTEGER, fecha_envio TEXT NOT NULL,
                enviado INTEGER NOT NULL DEFAULT 0, descargado INTEGER NOT NULL DEFAULT 0
            )
        """)
        conn.execute("INSERT INTO historial_local (id_mensaje, origen, destino, tipo, contenido, fecha_envio) VALUES ('old', 'A', 'B', 'MENSAJE_TEXTO', 'conservado', '2026-01-01')")
        conn.commit()
        conn.close()

        migrated = Database("sqlite:///" + tmp.name, esquema_path=ESQUEMA_CANONICO)
        with migrated.conexion() as conn:
            columnas = {r[1] for r in conn.execute("PRAGMA table_info(historial_local)")}
            fila = conn.execute("SELECT contenido, estado, archivo_id FROM historial_local WHERE id_mensaje='old'").fetchone()
        self.assertTrue({"estado", "archivo_id"} <= columnas)
        self.assertEqual(tuple(fila), ("conservado", "ENVIADO", None))

    def test_pendientes_con_predelete_e_intentos(self):
        self.h.guardar_pendiente("p1", "MENSAJE_TEXTO", "A", "B", "hola",
                                 "", None, "2026-01-01T10:00:00")
        # Re-registro del mismo id: upsert, no IntegrityError.
        self.h.guardar_pendiente("p1", "MENSAJE_TEXTO", "A", "B", "hola",
                                 "", None, "2026-01-01T10:00:00")
        self.assertEqual(len(self.h.obtener_pendientes()), 1)
        self.h.incrementar_intento("p1", "sin red")
        pend = self.h.obtener_pendientes()[0]
        self.assertEqual(pend["intentos"], 1)
        self.assertEqual(pend["ultimo_error"], "sin red")
        self.h.eliminar_pendiente("p1")
        self.assertEqual(self.h.obtener_pendientes(), [])

    def test_cache_conectados_primero(self):
        filas = self.h.listar_cache_usuarios()
        self.assertEqual([u["codigo"] for u in filas], ["A", "B"])
        self.h.marcar_conectados(["B"])
        filas = self.h.listar_cache_usuarios()
        self.assertEqual([u["codigo"] for u in filas], ["B", "A"])

    def test_esquema_canonico_en_uso(self):
        # El DDL ejecutado es el compartido: las 3 tablas + columnas existen.
        with self.db.conexion() as conn:
            tablas = {r[0] for r in conn.execute(
                "SELECT name FROM sqlite_master WHERE type='table'")}
        self.assertTrue({"historial_local", "cache_usuarios",
                         "pendientes_envio"} <= tablas)

    def test_ruta_para_cuenta_sanitizacion(self):
        from store.database import ruta_para_cuenta, sanitizar_cuenta
        self.assertEqual(sanitizar_cuenta("A001"), "A001")
        self.assertEqual(sanitizar_cuenta("user../@#1"), "user_____1")
        base = "sqlite:///C:/test/mensajeria_cliente.db"
        res = ruta_para_cuenta(base, "A001")
        self.assertIn("mensajeria_cliente_A001.db", res)

    def test_migracion_legado_a_cuentas(self):
        from store.database import migrar_legado_a_cuenta
        tmp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(tmp_dir.cleanup)
        legado_path = os.path.join(tmp_dir.name, "mensajeria_cliente.db")
        conn = sqlite3.connect(legado_path)
        conn.execute("""
            CREATE TABLE historial_local (
                id_mensaje TEXT PRIMARY KEY, origen TEXT NOT NULL, destino TEXT NOT NULL,
                tipo TEXT NOT NULL, contenido TEXT, hash_sha256 TEXT,
                num_caracteres INTEGER, num_palabras INTEGER, nombre_archivo TEXT,
                ruta_archivo TEXT, tamano_archivo INTEGER, fecha_envio TEXT NOT NULL,
                enviado INTEGER NOT NULL DEFAULT 0, descargado INTEGER NOT NULL DEFAULT 0,
                estado TEXT NOT NULL DEFAULT 'ENVIADO', archivo_id TEXT
            )
        """)
        conn.execute("""
            CREATE TABLE pendientes_envio (
                id_mensaje TEXT PRIMARY KEY, tipo TEXT NOT NULL, origen TEXT NOT NULL,
                destino TEXT NOT NULL, contenido TEXT, ruta_archivo TEXT,
                archivo_id TEXT, fecha_envio TEXT NOT NULL, intentos INTEGER NOT NULL DEFAULT 0,
                ultimo_error TEXT
            )
        """)
        conn.execute("""
            CREATE TABLE cache_usuarios (
                codigo TEXT PRIMARY KEY, nombres TEXT NOT NULL, apellidos TEXT NOT NULL,
                carrera TEXT NOT NULL, conectado INTEGER NOT NULL DEFAULT 0,
                fecha_creacion TEXT NOT NULL, ultima_conexion TEXT NOT NULL
            )
        """)
        # Fila de mensaje de A a B
        conn.execute("""
            INSERT INTO historial_local (id_mensaje, origen, destino, tipo, contenido, fecha_envio, enviado, estado)
            VALUES ('msg-ab-1', 'A', 'B', 'MENSAJE_TEXTO', 'De A para B', '2026-01-01T10:00:00', 1, 'ENVIADO')
        """)
        # Pendiente de origen A
        conn.execute("""
            INSERT INTO pendientes_envio (id_mensaje, tipo, origen, destino, contenido, fecha_envio)
            VALUES ('pend-a-1', 'MENSAJE_TEXTO', 'A', 'B', 'Pendiente de A', '2026-01-01T10:00:00')
        """)
        # Pendiente de origen B
        conn.execute("""
            INSERT INTO pendientes_envio (id_mensaje, tipo, origen, destino, contenido, fecha_envio)
            VALUES ('pend-b-1', 'MENSAJE_TEXTO', 'B', 'A', 'Pendiente de B', '2026-01-01T10:00:00')
        """)
        conn.execute("""
            INSERT INTO cache_usuarios (codigo, nombres, apellidos, carrera, conectado, fecha_creacion, ultima_conexion)
            VALUES ('A', 'Ana', 'Lopez', 'Sistemas', 1, '2026-01-01', '2026-01-01')
        """)
        conn.commit()
        conn.close()

        cuenta_a_path = os.path.join(tmp_dir.name, "mensajeria_cliente_A.db")
        cuenta_b_path = os.path.join(tmp_dir.name, "mensajeria_cliente_B.db")

        # Migrar para cuenta A
        migrar_legado_a_cuenta(legado_path, cuenta_a_path, "A", ESQUEMA_CANONICO)
        # Migrar para cuenta B
        migrar_legado_a_cuenta(legado_path, cuenta_b_path, "B", ESQUEMA_CANONICO)

        # En cuenta A: enviado=1 porque origen es A
        conn_a = sqlite3.connect(cuenta_a_path)
        try:
            row_a = conn_a.execute("SELECT id_mensaje, enviado, estado FROM historial_local WHERE id_mensaje='msg-ab-1'").fetchone()
            pend_a = [r[0] for r in conn_a.execute("SELECT id FROM pendientes_envio").fetchall()]
        finally:
            conn_a.close()
        self.assertEqual(row_a, ('msg-ab-1', 1, 'ENVIADO'))
        self.assertEqual(pend_a, ['pend-a-1'])

        # En cuenta B: enviado=0 porque origen es A (para B es mensaje recibido)
        conn_b = sqlite3.connect(cuenta_b_path)
        try:
            row_b = conn_b.execute("SELECT id_mensaje, enviado, estado FROM historial_local WHERE id_mensaje='msg-ab-1'").fetchone()
            pend_b = [r[0] for r in conn_b.execute("SELECT id FROM pendientes_envio").fetchall()]
        finally:
            conn_b.close()
        self.assertEqual(row_b, ('msg-ab-1', 0, 'ENVIADO'))
        self.assertEqual(pend_b, ['pend-b-1'])

        # Idempotencia: volver a ejecutar la migración no duplica ni falla
        migrar_legado_a_cuenta(legado_path, cuenta_a_path, "A", ESQUEMA_CANONICO)
        conn_a2 = sqlite3.connect(cuenta_a_path)
        total_a = conn_a2.execute("SELECT count(*) FROM historial_local").fetchone()[0]
        conn_a2.close()
        self.assertEqual(total_a, 1)

        # Archivo legado sigue intacto
        self.assertTrue(os.path.exists(legado_path))


if __name__ == '__main__':
    unittest.main()
