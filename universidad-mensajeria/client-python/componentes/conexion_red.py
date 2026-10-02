"""Red TCP del cliente Python (InterfazConexionRed → socket + PROTOCOLO.md).

Reglas espejo del cliente Java (Fase 5):
- Escritura sincronizada con lock (un hilo lector + hilos de UI/reintento).
- Demultiplexor: la trama con ``id`` pendiente completa su futuro; el resto
  va a callbacks. ``CLOSE_NOTICE`` SIEMPRE va a ``on_cierre`` (nunca completa
  un pendiente: tras un KICK el aviso llega con el mismo id de la solicitud).
- ``pedir()`` DEVUELVE el dict de respuesta (correlacionado por ``id``).
"""
import socket
import threading
import uuid
from transversal.protocolo import enviar, recibir

TIMEOUT_RESPUESTA_SEG = 15


class ConexionRed:
    def __init__(self, host, puerto, on_mensaje_recibido,
                 on_cierre=None, on_desconexion=None):
        self.host = host
        self.puerto = puerto
        self.sock = None
        self._pendientes = {}
        self._receptor_thread = None
        self.on_mensaje_recibido = on_mensaje_recibido
        self.on_cierre = on_cierre
        self.on_desconexion = on_desconexion
        self.conectado = False
        self.lock = threading.Lock()
        self._envio_lock = threading.Lock()

    def conectar(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.connect((self.host, int(self.puerto)))
        self.conectado = True
        self._receptor_thread = threading.Thread(target=self._recibir_loop, daemon=True)
        self._receptor_thread.start()

    def desconectar(self):
        self.conectado = False
        with self.lock:
            for req_info in self._pendientes.values():
                req_info['event'].set()
            self._pendientes.clear()
        if self.sock:
            try:
                self.sock.close()
            except OSError:
                pass
        self.sock = None

    def _recibir_loop(self):
        try:
            while self.conectado:
                msg = recibir(self.sock)
                self._encaminar(msg)
        except Exception:
            pass
        finally:
            was = self.conectado
            self.desconectar()
            if was and self.on_desconexion:
                try:
                    self.on_desconexion()
                except Exception:
                    pass

    def _encaminar(self, msg):
        # CLOSE_NOTICE nunca completa un pendiente (carrera del KICK: el aviso
        # viaja con el id de la solicitud y el ACK llega despues).
        if msg.get('tipo') == 'CLOSE_NOTICE':
            if self.on_cierre:
                try:
                    self.on_cierre(msg)
                except Exception:
                    pass
            return
        id_ = msg.get('id')
        with self.lock:
            # Sin pop: quien espera hace el pop tras despertar (si el receptor
            # hiciera pop aqui, pedir() jamas veria la respuesta).
            req_info = self._pendientes.get(id_) if id_ else None
        if req_info is not None:
            req_info['respuesta'] = msg
            req_info['event'].set()
            return
        if self.on_mensaje_recibido:
            try:
                self.on_mensaje_recibido(msg)
            except Exception:
                pass

    def pedir(self, msg, timeout=TIMEOUT_RESPUESTA_SEG):
        """Envia correlacionando por id y DEVUELVE la respuesta (o eleva)."""
        if not self.conectado or self.sock is None:
            raise ConnectionError("No hay conexion con el servidor")

        id_ = msg.get('id') or str(uuid.uuid4())
        msg['id'] = id_
        evento = threading.Event()
        with self.lock:
            self._pendientes[id_] = {'event': evento, 'respuesta': None}

        try:
            with self._envio_lock:
                enviar(self.sock, msg)
        except Exception:
            with self.lock:
                self._pendientes.pop(id_, None)
            raise

        if not evento.wait(timeout=timeout):
            with self.lock:
                self._pendientes.pop(id_, None)
            raise TimeoutError('sin respuesta del servidor')

        with self.lock:
            req_info = self._pendientes.pop(id_, None)
        if req_info is None or req_info['respuesta'] is None:
            # El socket se cerro mientras se esperaba.
            raise ConnectionError('conexion cerrada esperando respuesta')
        return req_info['respuesta']

    def enviar_async(self, msg):
        """Envio sin respuesta (solo LOGOUT). Todo lo demas usa pedir()."""
        if not self.conectado or self.sock is None:
            raise ConnectionError("No hay conexion con el servidor")
        if 'id' not in msg:
            msg['id'] = str(uuid.uuid4())
        with self._envio_lock:
            enviar(self.sock, msg)
