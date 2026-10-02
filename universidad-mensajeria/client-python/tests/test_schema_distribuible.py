import os
import sqlite3
import unittest


RAIZ = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", ".."))
ESQUEMAS = (
    os.path.join(RAIZ, "common-protocol", "dto", "src", "main", "resources", "schema-local.sql"),
    os.path.join(RAIZ, "client-python", "store", "schema-local.sql"),
)


def inspeccionar_esquema(ruta):
    conexion = sqlite3.connect(":memory:")
    with open(ruta, encoding="utf-8") as archivo:
        conexion.executescript(archivo.read())
    tablas = {}
    for (tabla,) in conexion.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
    ):
        columnas = tuple(tuple(fila) for fila in conexion.execute(
            f"PRAGMA table_info({tabla})"))
        indices = tuple(
            (nombre, tuple(fila[2] for fila in conexion.execute(
                f"PRAGMA index_info({nombre})")))
            for _, nombre, *_ in conexion.execute(f"PRAGMA index_list({tabla})")
        )
        tablas[tabla] = columnas, indices
    conexion.close()
    return tablas


class TestEsquemaDistribuible(unittest.TestCase):
    def test_copia_python_conserva_el_esquema_canonico(self):
        canonico, distribuible = (inspeccionar_esquema(ruta) for ruta in ESQUEMAS)
        self.assertEqual(distribuible, canonico)

    def test_esquema_incluido_existe_para_instalacion_independiente(self):
        self.assertTrue(os.path.isfile(ESQUEMAS[1]))
