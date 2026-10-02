import struct
import json

def enviar(sock, obj):
    payload = json.dumps(obj, ensure_ascii=False).encode('utf-8')
    sock.sendall(struct.pack('>I', len(payload)) + payload)

def recibir(sock):
    header = _recv_exact(sock, 4)
    if not header:
        raise ConnectionError('conexion cerrada')
    n = struct.unpack('>I', header)[0]
    if n <= 0 or n > 16 * 1024 * 1024:
        raise ValueError(f'longitud invalida: {n}')
    data = _recv_exact(sock, n)
    return json.loads(data.decode('utf-8'))

def _recv_exact(sock, n):
    buf = b''
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError('conexion cerrada')
        buf += chunk
    return buf
