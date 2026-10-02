"""Contrato de red: trama 4B big-endian + JSON y demultiplexor por id.

Se prueba contra sockets reales (socketpair) sin servidor.
"""
import json
import socket
import struct
import threading
import time
import unittest

from componentes.conexion_red import ConexionRed


def _leer_trama(sock):
    n = struct.unpack('>I', sock.recv(4))[0]
    buf = b''
    while len(buf) < n:
        buf += sock.recv(n - len(buf))
    return json.loads(buf.decode('utf-8'))


def _escribir_trama(sock, obj):
    p = json.dumps(obj).encode('utf-8')
    sock.sendall(struct.pack('>I', len(p)) + p)


class ServidorStub(threading.Thread):
    """Responde guionado por tipo; permite empujes asincronos."""

    def __init__(self):
        super().__init__(daemon=True)
        self.srv = socket.socket()
        self.srv.bind(('127.0.0.1', 0))
        self.srv.listen(1)
        self.puerto = self.srv.getsockname()[1]
        self.recibidos = []
        self.empujes = []
        self.conn = None

    def run(self):
        self.conn, _ = self.srv.accept()
        self.conn.settimeout(10)
        try:
            while True:
                req = _leer_trama(self.conn)
                self.recibidos.append(req)
                tipo = req.get('tipo')
                if tipo == 'LOGIN':
                    self._responder({'tipo': 'LOGIN_RESPUESTA', 'id': req.get('id'),
                                     'exito': True})
                elif tipo == 'MENSAJE_TEXTO':
                    time.sleep(0.2)  # ventana para el empuje asincrono
                    self._responder({'tipo': 'ACK', 'id': req.get('id'), 'exito': True})
                elif tipo == 'LISTAR_CONECTADOS':
                    self._responder({'tipo': 'LISTAR_CONECTADOS_RESPUESTA',
                                     'id': req.get('id'),
                                     'usuariosConectados': ['A001', 'B002']})
        except Exception:
            pass

    def _responder(self, obj):
        _escribir_trama(self.conn, obj)

    def empujar(self, obj):
        _escribir_trama(self.conn, obj)


class TestProtocolo(unittest.TestCase):
    def setUp(self):
        self.stub = ServidorStub()
        self.stub.start()
        self.recibidos = []
        self.cierres = []
        self.cn = ConexionRed('127.0.0.1', self.stub.puerto,
                              on_mensaje_recibido=self.recibidos.append,
                              on_cierre=self.cierres.append)
        self.cn.conectar()

    def tearDown(self):
        self.cn.desconectar()

    def test_pedir_devuelve_la_respuesta_correlacionada(self):
        resp = self.cn.pedir({'tipo': 'LOGIN', 'codigo': 'A001'})
        self.assertIsNotNone(resp)
        self.assertEqual(resp['tipo'], 'LOGIN_RESPUESTA')
        self.assertTrue(resp['exito'])

    def test_entrega_asincrona_no_rompe_el_ack(self):
        self.stub.empujar({'tipo': 'BROADCAST', 'id': 'empuje-1',
                           'remitente': 'C', 'contenido': 'aviso'})
        ack = self.cn.pedir({'tipo': 'MENSAJE_TEXTO', 'contenido': 'hola'})
        self.assertEqual(ack['tipo'], 'ACK')
        limite = time.time() + 5
        while not any(m.get('tipo') == 'BROADCAST' for m in self.recibidos) \
                and time.time() < limite:
            time.sleep(0.05)
        self.assertTrue(any(m.get('contenido') == 'aviso' for m in self.recibidos))

    def test_close_notice_va_al_callback_aunque_tenga_id(self):
        # Carrera del KICK: el aviso viaja con el id de la solicitud y el ACK
        # llega despues; el aviso jamas debe completar un pendiente.
        self.stub.empujar({'tipo': 'CLOSE_NOTICE', 'id': 'kick-1',
                           'mensajeError': 'kick'})
        limite = time.time() + 5
        while not self.cierres and time.time() < limite:
            time.sleep(0.05)
        self.assertEqual(len(self.cierres), 1)
        self.assertEqual(self.cierres[0]['mensajeError'], 'kick')
        # La conexion sigue util: un pedir posterior correlaciona bien.
        resp = self.cn.pedir({'tipo': 'LOGIN', 'codigo': 'A001'})
        self.assertTrue(resp['exito'])

    def test_timeout_sin_respuesta(self):
        with self.assertRaises(TimeoutError):
            self.cn.pedir({'tipo': 'INEXISTENTE'}, timeout=1)

    def test_escrituras_concurrentes_no_se_intercalan(self):
        # 10 hilos piden a la vez; cada respuesta debe llegar a su dueno.
        resultados = {}
        errores = []

        def worker(i):
            try:
                r = self.cn.pedir({'tipo': 'LISTAR_CONECTADOS'})
                resultados[i] = r
            except Exception as e:  # noqa: BLE001
                errores.append(e)

        hilos = [threading.Thread(target=worker, args=(i,)) for i in range(10)]
        for h in hilos:
            h.start()
        for h in hilos:
            h.join(15)
        self.assertEqual(errores, [])
        self.assertEqual(len(resultados), 10)
        for r in resultados.values():
            self.assertEqual(r['usuariosConectados'], ['A001', 'B002'])


if __name__ == '__main__':
    unittest.main()
