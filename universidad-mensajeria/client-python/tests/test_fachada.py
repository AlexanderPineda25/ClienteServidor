"""Fachada Python con red falsa y SQLite real: online, offline, reintento."""
import os
import tempfile
import unittest

from componentes.historial_local import HistorialLocal
from fachada.fachada_cliente import FachadaCliente, DESTINO_BROADCAST
from store.database import Database


class RedFalsa:
    """Guion programable: responde ACK o simula corte."""

    def __init__(self, *args, **kwargs):
        self.conectado = True
        self.enviados_async = []
        self.caida = False

    def conectar(self):
        self.conectado = True

    def pedir(self, msg, timeout=15):
        if self.caida or not self.conectado:
            raise ConnectionError("corte simulado")
        tipo = msg.get('tipo')
        if tipo == 'LOGIN':
            return {'tipo': 'LOGIN_RESPUESTA', 'id': msg.get('id'), 'exito': True}
        if tipo == 'MENSAJE_TEXTO':
            if msg.get('destinatario') == 'ZZZ':
                return {'tipo': 'ERROR', 'id': msg.get('id'),
                        'mensajeError': 'destinatario inexistente'}
            return {'tipo': 'ACK', 'id': msg.get('id'), 'exito': True,
                    'hashSha256': 'h', 'numCaracteres': 4, 'numPalabras': 1}
        if tipo == 'MENSAJE_IMAGEN':
            return {'tipo': 'IMAGE_FILTERED_RESULT', 'id': msg.get('id'),
                    'exito': True, 'hashSha256': 'hi', 'etapasFiltrado': '[]',
                    'archivoId': '7'}
        if tipo == 'BROADCAST':
            return {'tipo': 'ACK', 'id': msg.get('id'), 'exito': True}
        if tipo == 'LISTAR_CONECTADOS':
            return {'tipo': 'LISTAR_CONECTADOS_RESPUESTA', 'id': msg.get('id'),
                    'usuariosConectados': ['A001', 'B002']}
        return {'tipo': 'ERROR', 'mensajeError': 'no soportado en falsa'}

    def enviar_async(self, msg):
        self.enviados_async.append(msg)


class TestFachada(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.NamedTemporaryFile(suffix=".db", delete=False)
        tmp.close()
        self.addCleanup(os.unlink, tmp.name)
        esquema = os.path.normpath(os.path.join(
            os.path.dirname(os.path.abspath(__file__)),
            '..', '..', 'common-protocol', 'dto', 'src', 'main', 'resources',
            'schema-local.sql'))
        db = Database("sqlite:///" + tmp.name, esquema_path=esquema)
        self.red = RedFalsa()
        self.avisos = []
        self.presencias = []
        self.cierres = []
        self.f = FachadaCliente(on_mensaje_recibido_ui=self.avisos.append,
                                on_presencia_ui=self.presencias.append,
                                on_cierre_ui=self.cierres.append,
                                db=db, conexion=self.red)
        self.f.login("A001", "Secreta123")

    def test_enviar_texto_online_guarda_metadatos(self):
        self.assertTrue(self.f.enviar_texto("B002", "hola"))
        conv = self.f.obtener_conversacion("B002")
        self.assertEqual(len(conv), 1)
        self.assertEqual(conv[0]["contenido"], "hola")
        # Paridad Java: el ACK no trae hash; conteos locales.
        self.assertEqual(conv[0]["hash_sha256"], "")
        self.assertEqual(conv[0]["num_caracteres"], 4)
        self.assertEqual(conv[0]["num_palabras"], 1)
        self.assertEqual(self.f.pendientes(), [])

    def test_error_servidor_se_eleva_no_se_traga(self):
        with self.assertRaises(RuntimeError):
            self.f.enviar_texto("ZZZ", "hola")

    def test_enviar_offline_encola_y_reintenta_con_ack(self):
        self.red.caida = True
        self.assertFalse(self.f.enviar_texto("B002", "luego"))
        self.assertEqual(len(self.f.pendientes()), 1)
        self.red.caida = False
        self.assertEqual(self.f.reintentar_pendientes(), 1)
        self.assertEqual(self.f.pendientes(), [])

    def test_broadcast_propio_marcado_y_recibido_en_conversacion(self):
        self.f.difundir("aviso")
        propias = [m for m in self.f.historial.obtener_conversacion("A001", DESTINO_BROADCAST)]
        self.assertEqual(len(propias), 1)
        # Entrante de otro: destino = codigo propio (leccion Fase 5).
        self.f._handle_unsolicited_message(
            {'tipo': 'BROADCAST', 'id': 'bc-1', 'remitente': 'B002',
             'contenido': 'hola a todos', 'fechaHora': '2026-01-01T10:00:00'})
        conv = self.f.obtener_conversacion("B002")
        self.assertEqual(len(conv), 1)
        self.assertEqual(conv[0]["contenido"], "hola a todos")
        self.assertEqual(len(self.avisos), 1)

    def test_imagen_recibida_no_guarda_base64(self):
        self.f._handle_unsolicited_message(
            {'tipo': 'MENSAJE_IMAGEN', 'id': 'im-1', 'remitente': 'B002',
             'contenidoImagen': 'QUJD', 'nombreArchivo': 'f.png',
             'hashSha256': 'hi', 'fechaHora': '2026-01-01T10:00:00'})
        conv = self.f.obtener_conversacion("B002")
        self.assertIsNone(conv[0]["contenido"])

    def test_presencia_push_actualiza_callback_y_cierre_limpia_sesion(self):
        self.f._handle_unsolicited_message(
            {'tipo': 'PRESENCIA', 'codigo': 'B002', 'contenido': 'conectado',
             'usuariosConectados': ['A001', 'B002']})
        self.assertEqual(len(self.presencias), 1)

        self.f._handle_cierre({'tipo': 'CLOSE_NOTICE', 'mensajeError': 'inactividad'})
        self.assertIsNone(self.f.codigo_actual)
        self.assertEqual(self.cierres[0]['mensajeError'], 'inactividad')

    def test_persistencia_pre_envio_y_estado_enviado(self):
        # Enviar texto con id específico
        mid = "msg-persistencia-1"
        exito = self.f.enviar_texto("B002", "mensaje persistido", id_msg=mid)
        self.assertTrue(exito)
        msg = self.f.historial.obtener_por_id(mid)
        self.assertIsNotNone(msg)
        self.assertEqual(msg["estado"], "ENVIADO")

    def test_push_entregado_y_leido_mismo_id(self):
        mid = "msg-push-status"
        self.f.enviar_texto("B002", "mensaje prueba status", id_msg=mid)
        self.assertEqual(self.f.historial.obtener_por_id(mid)["estado"], "ENVIADO")

        # Push ENTREGADO
        self.f._handle_unsolicited_message({
            "tipo": "MENSAJE_ENTREGADO",
            "contenido": mid,
            "remitente": "B002"
        })
        self.assertEqual(self.f.historial.obtener_por_id(mid)["estado"], "ENTREGADO")

        # Push LEIDO
        self.f._handle_unsolicited_message({
            "tipo": "MENSAJE_LEIDO",
            "contenido": mid,
            "remitente": "B002"
        })
        self.assertEqual(self.f.historial.obtener_por_id(mid)["estado"], "LEIDO")

        # Intento de push desordenado a ENTREGADO no debe degradar LEIDO
        self.f._handle_unsolicited_message({
            "tipo": "MENSAJE_ENTREGADO",
            "contenido": mid,
            "remitente": "B002"
        })
        self.assertEqual(self.f.historial.obtener_por_id(mid)["estado"], "LEIDO")

    def test_fijar_cuenta_aisla_base_datos(self):
        self.f.fijar_cuenta("C003")
        self.assertEqual(self.f.codigo_actual, "C003")
        self.assertEqual(self.f.db.cuenta, "C003")
        # El historial de C003 debe estar vacío
        self.assertEqual(self.f.obtener_conversacion("B002"), [])


if __name__ == '__main__':
    unittest.main()
