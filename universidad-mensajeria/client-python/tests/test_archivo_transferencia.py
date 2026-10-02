"""Pruebas unitarias para transferencia fragmentada de archivos y borrado de registro (Fase 7 - Pista 2)."""
import base64
import hashlib
import os
import tempfile
import unittest
from pathlib import Path

from componentes.autenticacion import Autenticacion
from componentes.historial_local import HistorialLocal
from fachada.fachada_cliente import FachadaCliente, _PARTE_MAX_BYTES, _ARCHIVO_MAX_BYTES
from store.database import Database
import ui.registro_window


class RedFalsaArchivos:
    def __init__(self):
        self.conectado = True
        self.caida = False
        self.peticiones = []
        self.enviados_async = []

    def conectar(self):
        self.conectado = True

    def pedir(self, msg, timeout=15):
        if self.caida or not self.conectado:
            raise ConnectionError("corte de red simulado")
        self.peticiones.append(msg)
        tipo = msg.get("tipo")
        if tipo == "LOGIN":
            return {"tipo": "LOGIN_RESPUESTA", "id": msg.get("id"), "exito": True}
        if tipo == "ARCHIVO_INICIO":
            return {"tipo": "ACK", "id": msg.get("id"), "exito": True}
        if tipo == "ARCHIVO_PARTE":
            return {"tipo": "ACK", "id": msg.get("id"), "exito": True}
        if tipo == "ARCHIVO_FIN":
            return {"tipo": "ACK", "id": msg.get("id"), "exito": True, "archivoId": "arch-999"}
        if tipo == "DESCARGAR_ARCHIVO":
            contenido = base64.b64encode(b"contenido-archivo-descargado").decode("utf-8")
            return {
                "tipo": "DESCARGAR_ARCHIVO_RESPUESTA",
                "id": msg.get("id"),
                "exito": True,
                "archivoId": msg.get("archivoId"),
                "contenidoImagen": contenido,
                "nombreArchivo": "descargado.dat",
                "tamanoArchivo": len(b"contenido-archivo-descargado"),
            }
        return {"tipo": "ACK", "id": msg.get("id"), "exito": True}

    def enviar_async(self, msg):
        self.enviados_async.append(msg)


class TestArchivoTransferencia(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.NamedTemporaryFile(suffix=".db", delete=False)
        tmp.close()
        self.addCleanup(os.unlink, tmp.name)
        esquema = os.path.normpath(os.path.join(
            os.path.dirname(os.path.abspath(__file__)),
            '..', '..', 'common-protocol', 'dto', 'src', 'main', 'resources',
            'schema-local.sql'))
        db = Database("sqlite:///" + tmp.name, esquema_path=esquema)
        self.red = RedFalsaArchivos()
        self.f = FachadaCliente(db=db, conexion=self.red)
        self.f.login("A001", "clave")

    def test_registro_eliminado(self):
        """Paso F: El alta pública y métodos de registro fueron removidos."""
        self.assertFalse(hasattr(self.f, "registro"), "FachadaCliente no debe tener método registro")
        self.assertFalse(hasattr(self.f.autenticacion, "registro"), "Autenticacion no debe tener método registro")
        self.assertFalse(hasattr(ui.registro_window, "RegistroWindow"), "RegistroWindow debe estar eliminada")

    def test_enviar_archivo_fragmentado_exito(self):
        """Paso A: Envía archivo en partes ≤ 1 MiB con ARCHIVO_INICIO/PARTE/FIN."""
        # Creamos un archivo de prueba con tamaño mayor a 1 MiB (ej: 2.2 MiB -> 3 partes)
        tamano_total = int(2.2 * 1024 * 1024)
        datos_prueba = b"X" * tamano_total
        sha_esperado = hashlib.sha256(datos_prueba).hexdigest()

        tmp_arch = tempfile.NamedTemporaryFile(suffix=".pdf", delete=False)
        tmp_arch.write(datos_prueba)
        tmp_arch.close()
        self.addCleanup(os.unlink, tmp_arch.name)

        progreso = []
        def on_progreso(actual, total):
            progreso.append((actual, total))

        mid = "msg-arch-1"
        ok = self.f.enviar_archivo("B002", tmp_arch.name, id_msg=mid, on_progreso=on_progreso)
        self.assertTrue(ok)

        # Verificar progreso registrado: 3 partes
        self.assertEqual(len(progreso), 3)
        self.assertEqual(progreso[-1], (3, 3))

        # Verificar peticiones de red
        tipos = [p["tipo"] for p in self.red.peticiones if p["tipo"].startswith("ARCHIVO_")]
        self.assertEqual(tipos, ["ARCHIVO_INICIO", "ARCHIVO_PARTE", "ARCHIVO_PARTE", "ARCHIVO_PARTE", "ARCHIVO_FIN"])

        # Verificar ARCHIVO_INICIO
        inicio = self.red.peticiones[1]  # 0 fue LOGIN
        self.assertEqual(inicio["tipo"], "ARCHIVO_INICIO")
        self.assertEqual(inicio["id"], mid)
        self.assertEqual(inicio["totalPartes"], 3)
        self.assertEqual(inicio["tamano"], tamano_total)
        self.assertEqual(inicio["destinatario"], "B002")

        # Verificar partes
        for idx in range(3):
            parte_req = self.red.peticiones[2 + idx]
            self.assertEqual(parte_req["tipo"], "ARCHIVO_PARTE")
            self.assertEqual(parte_req["indice"], idx)
            decodificado = base64.b64decode(parte_req["base64"])
            self.assertLessEqual(len(decodificado), _PARTE_MAX_BYTES)

        # Verificar ARCHIVO_FIN
        fin = self.red.peticiones[5]
        self.assertEqual(fin["tipo"], "ARCHIVO_FIN")
        self.assertEqual(fin["sha256"], sha_esperado)

        # Verificar persistencia en historial local
        fila = self.f.historial.obtener_por_id(mid)
        self.assertIsNotNone(fila)
        self.assertEqual(fila["estado"], "ENVIADO")
        self.assertEqual(fila["archivo_id"], "arch-999")
        self.assertEqual(fila["tipo"], "MENSAJE_ARCHIVO")

    def test_enviar_archivo_rechaza_mayor_50mb(self):
        """Paso A: Archivo mayor a 50 MB se rechaza con ValueError antes de tocar la red."""
        tmp_grande = tempfile.NamedTemporaryFile(suffix=".bin", delete=False)
        # Escribir solo puntero/sparse o tamaño simulado
        tmp_grande.close()
        self.addCleanup(os.unlink, tmp_grande.name)

        # Crear archivo de más de 50 MB usando seek
        with open(tmp_grande.name, "wb") as f:
            f.seek(_ARCHIVO_MAX_BYTES + 1024)
            f.write(b"1")

        with self.assertRaises(ValueError) as ctx:
            self.f.enviar_archivo("B002", tmp_grande.name)
        self.assertIn("demasiado grande", str(ctx.exception))

    def test_enviar_archivo_offline_y_reintento(self):
        """Paso A: Si no hay red, guarda en pendientes y reintenta cuando regresa."""
        datos = b"Datos del archivo offline"
        tmp_arch = tempfile.NamedTemporaryFile(suffix=".txt", delete=False)
        tmp_arch.write(datos)
        tmp_arch.close()
        self.addCleanup(os.unlink, tmp_arch.name)

        self.red.caida = True
        mid = "msg-arch-offline"
        ok = self.f.enviar_archivo("B002", tmp_arch.name, id_msg=mid)
        self.assertFalse(ok)

        # Persistido con PENDIENTE
        fila = self.f.historial.obtener_por_id(mid)
        self.assertIsNotNone(fila)
        self.assertEqual(fila["estado"], "PENDIENTE")

        # Debe estar en pendientes_envio
        pendientes = self.f.pendientes()
        self.assertEqual(len(pendientes), 1)
        self.assertEqual(pendientes[0]["id"], mid)
        self.assertEqual(pendientes[0]["tipo"], "MENSAJE_ARCHIVO")

        # Reconectar y reintentar
        self.red.caida = False
        enviados = self.f.reintentar_pendientes()
        self.assertEqual(enviados, 1)

        # Ahora debe estar ENVIADO y sin pendientes
        self.assertEqual(len(self.f.pendientes()), 0)
        fila_actualizada = self.f.historial.obtener_por_id(mid)
        self.assertEqual(fila_actualizada["estado"], "ENVIADO")
        self.assertEqual(fila_actualizada["archivo_id"], "arch-999")

    def test_descargar_archivo(self):
        """Paso A: DESCARGAR_ARCHIVO guarda bytes en destino con on_progreso."""
        destino = tempfile.NamedTemporaryFile(suffix=".descargado", delete=False)
        destino.close()
        self.addCleanup(os.unlink, destino.name)

        progreso = []
        def on_prog(actual, total):
            progreso.append((actual, total))

        res = self.f.descargar_archivo("arch-123", destino.name, on_progreso=on_prog)
        self.assertEqual(res, destino.name)
        self.assertTrue(os.path.exists(destino.name))
        with open(destino.name, "rb") as f:
            self.assertEqual(f.read(), b"contenido-archivo-descargado")
        self.assertTrue(len(progreso) > 0)

    def test_recepcion_mensaje_archivo_unsolicited(self):
        """Paso A: Recepción no solicitada de MENSAJE_ARCHIVO persiste metadatos."""
        msg = {
            "id": "arch-remoto-1",
            "tipo": "MENSAJE_ARCHIVO",
            "remitente": "B002",
            "fechaHora": "2026-10-02T12:00:00",
            "nombreArchivo": "documento.pdf",
            "tamanoArchivo": 1048576,
            "archivoId": "arch-remoto-id",
        }
        self.f._handle_unsolicited_message(msg)

        fila = self.f.historial.obtener_por_id("arch-remoto-1")
        self.assertIsNotNone(fila)
        self.assertEqual(fila["tipo"], "MENSAJE_ARCHIVO")
        self.assertEqual(fila["contenido"], "[Archivo]")
        self.assertEqual(fila["nombre_archivo"], "documento.pdf")
        self.assertEqual(fila["tamano_archivo"], 1048576)
        self.assertEqual(fila["archivo_id"], "arch-remoto-id")
        self.assertEqual(fila["descargado"], 0)


if __name__ == "__main__":
    unittest.main()
