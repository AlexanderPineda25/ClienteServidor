import base64
import os
import tempfile
from pathlib import Path
import threading
import unittest

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QBuffer, QCoreApplication, QEvent, QEventLoop, QIODevice, QTimer, Qt
from PySide6.QtGui import QImage
from PySide6.QtWidgets import QApplication

from ui.chat_window import ChatWindow
from ui.login_window import LoginWindow


class FachadaFalsa:
    def __init__(self):
        self.on_mensaje_recibido_ui = None
        self.on_cierre_ui = None
        self.on_presencia_ui = None
        self.on_desconexion_ui = None
        self.enviados = []

    def login(self, codigo, contrasena):
        return {"exito": codigo == "A001" and contrasena == "clave"}

    def listar_conectados(self):
        return ["A001", "B002"]

    def directorio_local(self):
        return [{"codigo": "B002"}]

    def obtener_conversacion(self, usuario):
        return []

    def enviar_texto(self, usuario, texto):
        self.enviados.append(("texto", usuario, texto))

    def enviar_imagen(self, usuario, ruta):
        self.enviados.append(("imagen", usuario, ruta))

    def difundir(self, texto):
        self.enviados.append(("difusion", texto))

    def desconectar(self):
        pass


class PruebasUI(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def setUp(self):
        self.fachada = FachadaFalsa()
        self.login = LoginWindow(self.fachada)
        self.chat = ChatWindow(self.fachada, "A001", lambda: None)
        temp_images = tempfile.TemporaryDirectory()
        self.addCleanup(temp_images.cleanup)
        self.chat._directorio_imagenes = Path(temp_images.name)
        self._wait()

    def _wait(self, ms=350):
        loop = QEventLoop()
        QTimer.singleShot(ms, loop.quit)
        loop.exec()

    def tearDown(self):
        self.chat._cerrando = True
        self.chat.close()
        self.login.close()
        self.chat.deleteLater()
        self.login.deleteLater()
        QCoreApplication.sendPostedEvents(None, QEvent.Type.DeferredDelete)
        self.app.processEvents()

    def test_login_emite_codigo_solo_si_autentica(self):
        codigos = []
        self.login.login_succeeded.connect(codigos.append)
        self.login.entry_codigo.setText("A001")
        self.login.entry_password.setText("clave")
        self.login.login()
        self.assertEqual(["A001"], codigos)

    def test_chat_envia_a_usuario_seleccionado(self):
        self.chat.lista_usuarios.setCurrentRow(0)
        self.chat.entry_msg.setText("Hola")
        self.chat.enviar()
        self._wait()
        self.assertEqual([("texto", "B002", "Hola")], self.fachada.enviados)

    def test_evento_del_hilo_de_red_se_aplica_en_la_vista(self):
        self.chat.lista_usuarios.setCurrentRow(0)
        mensaje = {
            "tipo": "MENSAJE_TEXTO",
            "remitente": "B002",
            "fechaHora": "2026-10-01T10:00:00",
            "contenido": "recibido",
        }
        hilo = threading.Thread(target=lambda: self.fachada.on_mensaje_recibido_ui(mensaje))
        hilo.start()
        loop = QEventLoop()
        QTimer.singleShot(250, loop.quit)
        loop.exec()
        hilo.join()
        self._wait()
        contenidos = [self.chat.lista_mensajes.itemWidget(self.chat.lista_mensajes.item(i)).body.text()
                      for i in range(self.chat.lista_mensajes.count())]
        self.assertTrue(any("recibido" in texto for texto in contenidos))

    def test_mensaje_imagen_inserta_preview_en_historial(self):
        imagen = QImage(2, 2, QImage.Format.Format_ARGB32)
        imagen.fill(Qt.GlobalColor.darkGreen)
        buffer = QBuffer()
        buffer.open(QIODevice.OpenModeFlag.WriteOnly)
        imagen.save(buffer, "PNG")
        contenido = base64.b64encode(bytes(buffer.data())).decode("ascii")

        self.chat.lista_usuarios.setCurrentRow(0)
        self.chat.show()
        self.fachada.on_mensaje_recibido_ui({
            "tipo": "MENSAJE_IMAGEN", "id": "imagen-ui", "remitente": "B002",
            "fechaHora": "2026-10-01T10:00:00", "nombreArchivo": "preview.png",
            "contenidoImagen": contenido,
        })
        self._wait(500)
        row = self.chat._row_por_id("imagen-ui")
        self.assertIsNotNone(row)
        self.assertIsNotNone(row.image.pixmap())

    def test_responder_inserta_cita_legible(self):
        self.chat.lista_usuarios.setCurrentRow(0)
        self.chat._responder({"sender": "Luis García [B002]", "text": "hola", "is_image": False})
        self.assertIn('Respuesta a Luis García [B002]: "hola"', self.chat.entry_msg.text())

    def test_alineacion_burbujas_propia_y_recibida(self):
        self.chat.lista_usuarios.setCurrentRow(0)
        # Mensaje propio
        self.chat._agregar_fila({
            "id": "propio-1", "sender": "Tú", "is_own": True,
            "text": "mensaje mío", "state": "⏳ Pendiente", "raw_state": "PENDIENTE",
            "is_image": False, "date": "2026-10-01 10:00:00"
        })
        # Mensaje recibido
        self.chat._agregar_fila({
            "id": "recibido-1", "sender": "B002", "is_own": False,
            "text": "mensaje de B", "state": "", "raw_state": "",
            "is_image": False, "date": "2026-10-01 10:00:01"
        })
        row_propio = self.chat._row_por_id("propio-1")
        row_recibido = self.chat._row_por_id("recibido-1")

        self.assertTrue(row_propio.item["is_own"])
        self.assertFalse(row_recibido.item["is_own"])
        # Alineación a la derecha: el primer elemento del layout es un stretch
        self.assertIsNotNone(row_propio.layout().itemAt(0).spacerItem())
        # Alineación a la izquierda: el primer elemento es el bubble, el segundo es un stretch
        self.assertIsNone(row_recibido.layout().itemAt(0).spacerItem())
        self.assertIsNotNone(row_recibido.layout().itemAt(1).spacerItem())

    def test_estados_legibles_y_visto_azul(self):
        self.assertEqual(ChatWindow._estado_visible("PENDIENTE"), "⏳ Pendiente")
        self.assertEqual(ChatWindow._estado_visible("ENVIADO"), "✓ Enviado")
        self.assertEqual(ChatWindow._estado_visible("ENTREGADO"), "✓✓ Entregado")
        self.assertEqual(ChatWindow._estado_visible("LEIDO"), "✓✓ Leído")

        self.chat.lista_usuarios.setCurrentRow(0)
        self.chat._agregar_fila({
            "id": "estado-test", "sender": "Tú", "is_own": True,
            "text": "verificando color", "state": "✓✓ Leído", "raw_state": "LEIDO",
            "is_image": False, "date": "2026-10-01 10:00:00"
        })
        row = self.chat._row_por_id("estado-test")
        self.assertIn("#1E88E5", row.status.styleSheet())

    def test_adjunto_preview_y_quitar(self):
        self.chat.show()
        self.chat.lista_usuarios.setCurrentRow(0)
        imagen = QImage(16, 16, QImage.Format.Format_ARGB32)
        imagen.fill(Qt.GlobalColor.blue)
        tmp = tempfile.NamedTemporaryFile(suffix=".png", delete=False)
        tmp.close()
        self.addCleanup(os.unlink, tmp.name)
        imagen.save(tmp.name, "PNG")

        self.chat._fijar_imagen_adjunta(tmp.name)
        self.assertEqual(self.chat.imagen_pendiente, str(Path(tmp.name).resolve()))
        self.assertFalse(self.chat.preview_container.isHidden())
        self.assertFalse(self.chat.lbl_preview_thumb.pixmap().isNull())
        self.assertIn(Path(tmp.name).name, self.chat.lbl_adjunto.text())

        # Quitar adjunto
        self.chat._quitar_adjunto()
        self.assertIsNone(self.chat.imagen_pendiente)
        self.assertTrue(self.chat.preview_container.isHidden())

    def test_envio_optimista_inmediato(self):
        self.chat.lista_usuarios.setCurrentRow(0)
        self.chat.entry_msg.setText("Texto optimista")
        self.chat.enviar()

        # Inmediatamente en la lista debe haber aparecido el mensaje con estado PENDIENTE
        count = self.chat.lista_mensajes.count()
        self.assertGreater(count, 0)
        ultimo_item = self.chat.lista_mensajes.item(count - 1)
        data = ultimo_item.data(Qt.ItemDataRole.UserRole)
        self.assertEqual(data["text"], "Texto optimista")
        self.assertEqual(data["raw_state"], "PENDIENTE")
        self.assertEqual(data["state"], "⏳ Pendiente")
        self.assertTrue(data["is_own"])
        # Y el composer debe haberse limpiado de inmediato
        self.assertEqual(self.chat.entry_msg.text(), "")


if __name__ == "__main__":
    unittest.main()
