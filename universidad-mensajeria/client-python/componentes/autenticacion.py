import datetime
import uuid

class Autenticacion:
    def __init__(self, conexion):
        self.conexion = conexion

    def login(self, codigo, contrasena):
        req = {
            "tipo": "LOGIN",
            "codigo": codigo,
            "contrasena": contrasena,
            "fechaHora": datetime.datetime.now().isoformat()
        }
        resp = self.conexion.pedir(req)
        if resp.get("tipo") == "LOGIN_RESPUESTA":
            return resp
        raise Exception("Respuesta inesperada")


