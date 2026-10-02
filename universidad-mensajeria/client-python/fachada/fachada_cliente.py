"""Fachada del cliente Python (unico punto de entrada de la UI, §7.2).

Espejo de FachadaCliente Java (Fase 5):
- Enviado con red → pedir() + ACK; guarda local con metadatos del servidor.
- Sin red → guarda local + pendiente (reintento con ACK al reconectar).
- Recibido (texto/imagen/broadcast) → persiste con destino = codigo propio.
- Errores del servidor (destinatario invalido, limites) se elevan, no se
  tragan: el fire-and-forget quedo reservado solo para LOGOUT.
"""
import datetime
import uuid
import base64
import binascii
import hashlib
import mimetypes
import os
import json
from pathlib import Path
from componentes.conexion_red import ConexionRed
from componentes.autenticacion import Autenticacion
from componentes.historial_local import HistorialLocal
from store.database import Database
from transversal.config import Config

DESTINO_BROADCAST = "*"
# Fragmentación de archivos (contrato congelado Pista 1)
_PARTE_MAX_BYTES = 1 * 1024 * 1024   # 1 MiB de datos crudos por parte
_ARCHIVO_MAX_BYTES = 50 * 1024 * 1024  # límite: 50 MB


class FachadaCliente:
    def __init__(self, on_mensaje_recibido_ui=None, on_cierre_ui=None,
                 on_desconexion_ui=None, config=None, db=None, conexion=None,
                 autenticacion=None, historial=None, on_presencia_ui=None):
        self.config = config or Config()
        self.db = db or Database(self.config.get("db.url", "sqlite:///~/mensajeria_cliente.db"))
        self.historial = historial or HistorialLocal(self.db)
        self.on_mensaje_recibido_ui = on_mensaje_recibido_ui
        self.on_cierre_ui = on_cierre_ui
        self.on_desconexion_ui = on_desconexion_ui
        self.on_presencia_ui = on_presencia_ui
        self.conexion = conexion or ConexionRed(
            self.config.get("host", "localhost"),
            self.config.get("puerto", 5000),
            self._handle_unsolicited_message,
            on_cierre=self._handle_cierre,
            on_desconexion=self._handle_desconexion
        )
        self.autenticacion = autenticacion or Autenticacion(self.conexion)
        self.codigo_actual = None

    # ---------------------------------------------------------- ciclo de vida
    def conectar(self):
        self.conexion.conectar()

    def desconectar(self):
        if self.codigo_actual:
            try:
                self.conexion.enviar_async({
                    "tipo": "LOGOUT",
                    "codigo": self.codigo_actual,
                    "fechaHora": datetime.datetime.now().isoformat()
                })
            except Exception:
                pass
        self.conexion.desconectar()
        self.codigo_actual = None

    def login(self, codigo, contrasena):
        if not self.conexion.conectado:
            self.conectar()
        resp = self.autenticacion.login(codigo, contrasena)
        if resp.get("exito"):
            self.fijar_cuenta(codigo)
        return resp

    def fijar_cuenta(self, codigo):
        self.codigo_actual = codigo
        if hasattr(self.db, "para_cuenta"):
            self.db = self.db.para_cuenta(codigo)
            self.historial = HistorialLocal(self.db)
        self._asegurar_en_cache(codigo)
        self._procesar_pendientes()

    # ------------------------------------------------------------------ red
    def listar_conectados(self):
        self._exigir_sesion()
        req = {
            "tipo": "LISTAR_CONECTADOS",
            "codigo": self.codigo_actual,
            "fechaHora": datetime.datetime.now().isoformat()
        }
        resp = self.conexion.pedir(req)
        conectados = resp.get("usuariosConectados") or []
        self.historial.marcar_conectados(conectados)
        for codigo in conectados:
            self._asegurar_en_cache(codigo)
        return conectados

    def listar_usuarios(self):
        """Directorio completo desde LISTAR_USUARIOS_RESPUESTA.contenido (JSON).

        El servidor envia el arreglo en `contenido`, no en `usuarios`
        (ListarCaso.java, igual que lee FachadaCliente Java). Si la red
        falla o el contenido es ilegible/vacio se conserva la cache local.
        """
        self._exigir_sesion()
        resp = self.conexion.pedir({
            "tipo": "LISTAR_USUARIOS",
            "codigo": self.codigo_actual,
            "fechaHora": datetime.datetime.now().isoformat()
        })
        contenido = resp.get("contenido")
        if not contenido or not str(contenido).strip():
            raise RuntimeError("directorio de usuarios no disponible")
        try:
            filas = json.loads(contenido)
        except (TypeError, ValueError) as error:
            raise RuntimeError("directorio de usuarios ilegible") from error
        if not isinstance(filas, list):
            raise RuntimeError("directorio de usuarios ilegible")
        ahora = datetime.datetime.now().isoformat()
        usuarios = []
        for usuario in filas:
            if not isinstance(usuario, dict):
                continue
            codigo = usuario.get("codigo")
            if not codigo or not str(codigo).strip():
                continue
            usuarios.append({
                "codigo": codigo,
                "nombres": usuario.get("nombres") or "",
                "apellidos": usuario.get("apellidos") or "",
                "programa": usuario.get("programa") or "",
                "conectado": bool(usuario.get("conectado")),
            })
        if not usuarios:
            raise RuntimeError("directorio incompleto; se conserva la caché local")
        for usuario in usuarios:
            self.historial.actualizar_cache_usuario(
                usuario["codigo"], usuario["nombres"], usuario["apellidos"],
                usuario["programa"], int(usuario["conectado"]), "", ahora)
        self.historial.marcar_conectados(
            [u["codigo"] for u in usuarios if u["conectado"]])
        return self.historial.listar_cache_usuarios()

    def expulsar_otras_sesiones(self):
        self._exigir_sesion()
        resp = self.conexion.pedir({
            "tipo": "KICK", "codigo": self.codigo_actual,
            "fechaHora": datetime.datetime.now().isoformat()
        })
        self._lanzar_si_error(resp, "no se pudieron cerrar las otras sesiones")
        return True

    def enviar_texto(self, destinatario, texto, id_msg=None):
        """True si salio por red (ACK); False si quedo en pendientes."""
        self._exigir_sesion()
        if not destinatario or not texto:
            raise ValueError("destinatario y contenido son obligatorios")
        id_msg = id_msg or str(uuid.uuid4())
        fecha = datetime.datetime.now().isoformat()

        # Persistir ANTES del envio con estado PENDIENTE
        self.historial.guardar_mensaje(
            id_msg, self.codigo_actual, destinatario, "MENSAJE_TEXTO", texto,
            fecha, num_caracteres=len(texto), num_palabras=len(texto.split()),
            enviado=1, descargado=1, estado="PENDIENTE")

        req = {
            "id": id_msg,
            "tipo": "MENSAJE_TEXTO",
            "remitente": self.codigo_actual,
            "destinatario": destinatario,
            "contenido": texto,
            "fechaHora": fecha
        }
        try:
            resp = self.conexion.pedir(req)
        except (ConnectionError, TimeoutError, OSError):
            self.historial.guardar_pendiente(id_msg, "MENSAJE_TEXTO",
                                             self.codigo_actual, destinatario,
                                             texto, "", None, fecha)
            return False

        try:
            self._lanzar_si_error(resp, "envio rechazado")
        except Exception:
            self.historial.marcar_estado(id_msg, "ERROR")
            raise

        # Paridad Java: el ACK no trae hash; conteos locales.
        # Marcar estado ENVIADO (monotónico: no degrada si ya llegó ENTREGADO o LEIDO)
        self.historial.marcar_estado(id_msg, "ENVIADO")
        return True

    def enviar_imagen(self, destinatario, ruta_archivo, id_msg=None):
        """True si salio por red (IMAGE_FILTERED_RESULT); False si pendiente."""
        self._exigir_sesion()
        if not destinatario:
            raise ValueError("destinatario es obligatorio")
        with open(ruta_archivo, "rb") as f:
            crudos = f.read()
        if not crudos:
            raise ValueError("la imagen esta vacia")
        b64 = base64.b64encode(crudos).decode('utf-8')
        id_msg = id_msg or str(uuid.uuid4())
        fecha = datetime.datetime.now().isoformat()
        nombre = ruta_archivo.replace('\\', '/').split('/')[-1]

        # Persistir ANTES del envio con estado PENDIENTE
        self.historial.guardar_mensaje(
            id_msg, self.codigo_actual, destinatario, "MENSAJE_IMAGEN",
            "[Imagen]", fecha, nombre_archivo=nombre, ruta_archivo=ruta_archivo,
            tamano_archivo=len(crudos), enviado=1, descargado=1, estado="PENDIENTE")

        req = {
            "id": id_msg,
            "tipo": "MENSAJE_IMAGEN",
            "remitente": self.codigo_actual,
            "destinatario": destinatario,
            "contenidoImagen": b64,
            "nombreArchivo": nombre,
            "fechaHora": fecha
        }
        try:
            resp = self.conexion.pedir(req)
        except (ConnectionError, TimeoutError, OSError):
            self.historial.guardar_pendiente(id_msg, "MENSAJE_IMAGEN",
                                             self.codigo_actual, destinatario,
                                             "", nombre, crudos, fecha)
            return False

        try:
            self._lanzar_si_error(resp, "envio rechazado", ok_tipos=("IMAGE_FILTERED_RESULT",))
        except Exception:
            self.historial.marcar_estado(id_msg, "ERROR")
            raise

        archivo_id = resp.get("archivoId")
        hash_sha256 = resp.get("hashSha256") or ""
        if archivo_id:
            self.historial.actualizar_archivo_id(id_msg, archivo_id)
        if hash_sha256:
            self.historial.actualizar_hash(id_msg, hash_sha256)
        self.historial.marcar_estado(id_msg, "ENVIADO")
        return True

    def enviar_archivo(self, destinatario, ruta_archivo, id_msg=None, on_progreso=None):
        """Envía cualquier archivo usando el protocolo fragmentado (contrato Pista 1).

        Protocolo: ARCHIVO_INICIO → N × ARCHIVO_PARTE → ARCHIVO_FIN (ACK).
        Partes de hasta 1 MiB de datos crudos (base64 ocupa ~1.33 MiB en trama).
        Rechaza archivos >50 MB antes de intentar el envío.
        on_progreso(partes_enviadas, total_partes) se llama tras cada parte.
        """
        self._exigir_sesion()
        if not destinatario:
            raise ValueError("destinatario es obligatorio")
        ruta = Path(ruta_archivo)
        datos = ruta.read_bytes()
        if len(datos) > _ARCHIVO_MAX_BYTES:
            raise ValueError(
                f"Archivo demasiado grande: {len(datos)/1024/1024:.1f} MB (máx 50 MB)")
        sha256 = hashlib.sha256(datos).hexdigest()
        nombre = ruta.name
        mime = mimetypes.guess_type(str(ruta))[0] or "application/octet-stream"
        id_msg = id_msg or str(uuid.uuid4())
        fecha = datetime.datetime.now().isoformat()
        partes = [datos[i:i + _PARTE_MAX_BYTES]
                  for i in range(0, len(datos), _PARTE_MAX_BYTES)]
        total_partes = max(1, len(partes))

        # Persistir ANTES del envío con estado PENDIENTE
        self.historial.guardar_mensaje(
            id_msg, self.codigo_actual, destinatario, "MENSAJE_ARCHIVO",
            "[Archivo]", fecha, nombre_archivo=nombre,
            tamano_archivo=len(datos), enviado=1, descargado=1, estado="PENDIENTE")

        try:
            # ARCHIVO_INICIO (claves del contrato: nombreArchivo, tamanoArchivo…)
            inicio = self.conexion.pedir({
                "id": id_msg,
                "tipo": "ARCHIVO_INICIO",
                "remitente": self.codigo_actual,
                "destinatario": destinatario,
                "nombreArchivo": nombre,
                "mime": mime,
                "tamanoArchivo": len(datos),
                "totalPartes": total_partes,
                "fechaHora": fecha,
            })
            self._lanzar_si_error(inicio, "inicio de transferencia rechazado")

            # ARCHIVO_PARTE × N (archivoId = id de transferencia, una en vuelo)
            for i, parte in enumerate(partes):
                parte_resp = self.conexion.pedir({
                    "id": f"{id_msg}#p{i}",
                    "tipo": "ARCHIVO_PARTE",
                    "remitente": self.codigo_actual,
                    "archivoId": id_msg,
                    "indiceParte": i,
                    "contenidoImagen": base64.b64encode(parte).decode("utf-8"),
                    "fechaHora": fecha,
                })
                self._lanzar_si_error(parte_resp, f"parte {i + 1}/{total_partes} rechazada")
                if on_progreso:
                    on_progreso(i + 1, total_partes)

            # ARCHIVO_FIN — el servidor valida SHA-256, encola y responde ACK.
            # El archivoId real lo recibe el destinatario en el MENSAJE_ARCHIVO.
            resp = self.conexion.pedir({
                "id": f"{id_msg}#fin",
                "tipo": "ARCHIVO_FIN",
                "remitente": self.codigo_actual,
                "archivoId": id_msg,
                "hashSha256": sha256,
                "fechaHora": fecha,
            })
        except (ConnectionError, TimeoutError, OSError):
            self.historial.guardar_pendiente(
                id_msg, "MENSAJE_ARCHIVO",
                self.codigo_actual, destinatario,
                "", nombre, datos, fecha)
            return False

        try:
            self._lanzar_si_error(resp, "archivo rechazado")
        except Exception:
            self.historial.marcar_estado(id_msg, "ERROR")
            raise

        archivo_id = resp.get("archivoId")
        if archivo_id:
            self.historial.actualizar_archivo_id(id_msg, archivo_id)
        self.historial.marcar_estado(id_msg, "ENVIADO")
        return True

    def difundir(self, texto):
        self._exigir_sesion()
        if not texto:
            raise ValueError("el contenido no puede estar vacio")
        id_msg = str(uuid.uuid4())
        fecha = datetime.datetime.now().isoformat()
        resp = self.conexion.pedir({
            "id": id_msg,
            "tipo": "BROADCAST",
            "remitente": self.codigo_actual,
            "contenido": texto,
            "fechaHora": fecha
        })
        self._lanzar_si_error(resp, "difusion rechazada")
        self.historial.guardar_mensaje(id_msg, self.codigo_actual, DESTINO_BROADCAST,
                                       "BROADCAST", texto, fecha)
        return True

    def descargar_archivo(self, archivo_id, ruta_destino, on_progreso=None):
        """DESCARGAR_ARCHIVO bajo demanda (Fase 6/§7.2): guarda bytes en disco.

        on_progreso(bytes_escritos, total_bytes) se llama al finalizar la descarga.
        Retorna ruta_destino escrita.
        """
        self._exigir_sesion()
        if archivo_id is None or str(archivo_id).strip() == "":
            raise RuntimeError("la descarga necesita el identificador del archivo")
        resp = self.conexion.pedir({
            "tipo": "DESCARGAR_ARCHIVO",
            "archivoId": str(archivo_id),
            "fechaHora": datetime.datetime.now().isoformat()
        })
        self._lanzar_si_error(resp, "descarga rechazada",
                              ok_tipos=("DESCARGAR_ARCHIVO_RESPUESTA",))
        b64 = resp.get("contenidoImagen") or ""
        datos = base64.b64decode(b64)
        with open(ruta_destino, "wb") as f:
            f.write(datos)
        if on_progreso:
            on_progreso(len(datos), len(datos))
        return ruta_destino

    def marcar_descargado(self, id_mensaje, ruta_archivo):
        self.historial.marcar_descargado(id_mensaje, ruta_archivo)

    def obtener_imagen(self, id_mensaje, ruta_destino):
        """Carga una imagen desde caché o la solicita por archivoId."""
        fila = self.historial.obtener_por_id(id_mensaje)
        if not fila:
            raise RuntimeError("imagen no encontrada en el historial")
        ruta_local = fila.get("ruta_archivo")
        if ruta_local and os.path.isfile(ruta_local):
            return ruta_local
        archivo_id = fila.get("archivo_id")
        if not archivo_id:
            contenido = fila.get("contenido") or ""
            try:
                crudos = base64.b64decode(contenido, validate=True)
            except (binascii.Error, ValueError):
                raise RuntimeError("el servidor no entregó el identificador de esta imagen")
            if not crudos:
                raise RuntimeError("la imagen local está vacía")
            with open(ruta_destino, "wb") as imagen:
                imagen.write(crudos)
            self.marcar_descargado(id_mensaje, ruta_destino)
            return ruta_destino
        ruta = self.descargar_archivo(archivo_id, ruta_destino)
        self.marcar_descargado(id_mensaje, ruta)
        return ruta

    def marcar_leidos(self, otro):
        self._exigir_sesion()
        for id_mensaje in self.historial.ids_no_leidos(self.codigo_actual, otro):
            id_pendiente = "lectura:" + id_mensaje
            if not any(p["id"] == id_pendiente for p in self.historial.obtener_pendientes()):
                self.historial.guardar_pendiente(
                    id_pendiente, "MENSAJE_LEIDO", self.codigo_actual, otro,
                    id_mensaje, None, None, datetime.datetime.now().isoformat())
        self.historial.marcar_estado_de_conversacion(self.codigo_actual, otro, "LEIDO")
        return self.reintentar_pendientes()

    # -------------------------------------------------------------- historial
    def obtener_conversacion(self, destinatario, limit=50, offset=0):
        return self.historial.obtener_conversacion(self.codigo_actual, destinatario,
                                                   limit=limit, offset=offset)

    def contar_conversacion(self, destinatario):
        return self.historial.contar_conversacion(self.codigo_actual, destinatario)

    def traer_historial(self, destinatario, pagina):
        self._exigir_sesion()
        resp = self.conexion.pedir({
            "tipo": "HISTORIAL_REQ", "remitente": self.codigo_actual,
            "destinatario": destinatario, "pagina": pagina,
            "fechaHora": datetime.datetime.now().isoformat()
        })
        self._lanzar_si_error(resp, "historial rechazado", ok_tipos=("HISTORIAL_PAGE",))
        contenido = resp.get("contenido") or "[]"
        try:
            filas = json.loads(contenido)
        except (TypeError, ValueError) as error:
            raise RuntimeError("pagina remota ilegible") from error
        for fila in filas:
            id_mensaje = fila.get("id")
            origen = fila.get("remitente")
            destino = fila.get("destinatario")
            tipo = fila.get("tipo")
            if not id_mensaje or not origen or not destino or not tipo:
                continue
            propio = origen == self.codigo_actual
            es_img = (tipo == "MENSAJE_IMAGEN")
            es_arch = (tipo == "MENSAJE_ARCHIVO")
            contenido_hist = "[Imagen]" if es_img else ("[Archivo]" if es_arch else fila.get("contenido"))
            fecha_remota = fila.get("fechaEnvio") or datetime.datetime.now().isoformat()
            # La fila viva (UUID del remitente) y la remota (id numerico del
            # servidor) son el mismo mensaje: fusionar, no duplicar burbujas.
            conocido = self.historial.buscar_equivalente(
                origen, destino, tipo, fila.get("hashSha256") or "",
                fila.get("contenido") or "", fecha_remota)
            if conocido:
                self.historial.actualizar_metadatos_remotos(
                    conocido, hash_sha256=fila.get("hashSha256") or None,
                    num_caracteres=fila.get("numCaracteres"),
                    num_palabras=fila.get("numPalabras"),
                    nombre_archivo=fila.get("nombreArchivo") or None,
                    tamano_archivo=fila.get("tamanoArchivo"),
                    archivo_id=fila.get("archivoId"),
                    estado="ENVIADO" if propio else "ENTREGADO")
                continue
            self.historial.guardar_mensaje(
                id_mensaje, origen, destino, tipo,
                contenido_hist,
                fecha_remota,
                hash_sha256=fila.get("hashSha256") or "",
                num_caracteres=fila.get("numCaracteres") or 0,
                num_palabras=fila.get("numPalabras") or 0,
                nombre_archivo=fila.get("nombreArchivo") or "",
                tamano_archivo=fila.get("tamanoArchivo") or 0,
                enviado=1 if propio else 0,
                archivo_id=fila.get("archivoId"),
                estado="ENVIADO" if propio else "ENTREGADO")
        return int(resp.get("totalPaginas") or 0)

    def pendientes(self):
        return self.historial.obtener_pendientes()

    def reintentar_pendientes(self):
        """Reenvia pendientes en orden con ACK; devuelve cuantos salieron."""
        if not self.codigo_actual or not self.conexion.conectado:
            return 0
        enviados = 0
        for p in self.historial.obtener_pendientes():
            if p["tipo"] == "MENSAJE_LEIDO":
                req = {
                    "id": p["id"], "tipo": "MENSAJE_LEIDO",
                    "remitente": p["origen"], "destinatario": p["destino"],
                    "contenido": p["contenido"],
                    "fechaHora": p["fecha_creado"]
                }
                try:
                    self.conexion.enviar_async(req)
                    self.historial.eliminar_pendiente(p["id"])
                    enviados += 1
                except (ConnectionError, TimeoutError, OSError) as e:
                    self.historial.incrementar_intento(p["id"], str(e))
                    break
                continue

            if p["tipo"] == "MENSAJE_ARCHIVO":
                datos = p["payload"] or b""
                sha256 = hashlib.sha256(datos).hexdigest()
                nombre = p["nombre_archivo"] or "archivo"
                mime = mimetypes.guess_type(nombre)[0] or "application/octet-stream"
                partes = [datos[i:i + _PARTE_MAX_BYTES] for i in range(0, len(datos), _PARTE_MAX_BYTES)]
                total_partes = max(1, len(partes))
                try:
                    self.conexion.pedir({
                        "id": p["id"],
                        "tipo": "ARCHIVO_INICIO",
                        "remitente": p["origen"],
                        "destinatario": p["destino"],
                        "nombre": nombre,
                        "mime": mime,
                        "tamano": len(datos),
                        "totalPartes": total_partes,
                        "fechaHora": p["fecha_creado"],
                    })
                    for i, parte in enumerate(partes):
                        self.conexion.pedir({
                            "id": p["id"],
                            "tipo": "ARCHIVO_PARTE",
                            "indice": i,
                            "base64": base64.b64encode(parte).decode("utf-8"),
                        })
                    resp = self.conexion.pedir({
                        "id": p["id"],
                        "tipo": "ARCHIVO_FIN",
                        "sha256": sha256,
                    })
                    self._lanzar_si_error(resp, "reintento archivo rechazado")
                    if resp.get("archivoId"):
                        self.historial.actualizar_archivo_id(p["id"], resp["archivoId"])
                    self.historial.marcar_estado(p["id"], "ENVIADO")
                    self.historial.eliminar_pendiente(p["id"])
                    enviados += 1
                except (ConnectionError, TimeoutError) as e:
                    self.historial.incrementar_intento(p["id"], str(e))
                    break
                except RuntimeError as e:
                    self.historial.incrementar_intento(p["id"], str(e))
                    break
                continue

            req = {
                "id": p["id"],
                "tipo": p["tipo"],
                "remitente": p["origen"],
                "destinatario": p["destino"],
                "fechaHora": p["fecha_creado"]
            }
            if p["tipo"] == "MENSAJE_TEXTO":
                req["contenido"] = p["contenido"]
            elif p["tipo"] == "MENSAJE_IMAGEN":
                payload = p["payload"] or b""
                req["contenidoImagen"] = base64.b64encode(payload).decode('utf-8')
                req["nombreArchivo"] = p["nombre_archivo"]
            try:
                resp = self.conexion.pedir(req)
                self._lanzar_si_error(resp, "reintento rechazado")
                if p["tipo"] == "MENSAJE_IMAGEN" and resp.get("archivoId"):
                    self.historial.actualizar_archivo_id(p["id"], resp["archivoId"])
                if p["tipo"] in ("MENSAJE_IMAGEN", "MENSAJE_TEXTO"):
                    self.historial.marcar_estado(p["id"], "ENVIADO")
                self.historial.eliminar_pendiente(p["id"])
                enviados += 1
            except (ConnectionError, TimeoutError) as e:
                self.historial.incrementar_intento(p["id"], str(e))
                break
            except RuntimeError as e:
                self.historial.incrementar_intento(p["id"], str(e))
                break
        return enviados


    def directorio_local(self):
        return self.historial.listar_cache_usuarios()

    # ---------------------------------------------------------------- interno
    def _procesar_pendientes(self):
        try:
            self.reintentar_pendientes()
        except Exception:
            pass

    def _exigir_sesion(self):
        if not self.codigo_actual:
            raise RuntimeError("inicia sesion primero")

    @staticmethod
    def _lanzar_si_error(resp, defecto, ok_tipos=("ACK",)):
        if resp.get("tipo") in ok_tipos and resp.get("exito", True):
            return
        if resp.get("tipo") == "ERROR" or not resp.get("exito", True):
            raise RuntimeError(resp.get("mensajeError") or defecto)
        # Respuesta con tipo inesperado pero sin error: se acepta (tolerante).

    def _guardar_propio(self, id_msg, destinatario, tipo, contenido, fecha):
        self.historial.guardar_mensaje(id_msg, self.codigo_actual, destinatario,
                                       tipo, contenido, fecha)

    def _asegurar_en_cache(self, codigo):
        ahora = datetime.datetime.now().isoformat()
        existentes = {u["codigo"] for u in self.historial.listar_cache_usuarios()}
        if codigo not in existentes:
            self.historial.actualizar_cache_usuario(codigo, codigo, "", "", 0, ahora, ahora)

    def _handle_unsolicited_message(self, msg):
        tipo = msg.get("tipo")
        if tipo in ("MENSAJE_ENTREGADO", "MENSAJE_LEIDO"):
            id_mensaje = msg.get("contenido")
            if id_mensaje:
                self.historial.marcar_estado(
                    id_mensaje, "ENTREGADO" if tipo == "MENSAJE_ENTREGADO" else "LEIDO")
            if self.on_mensaje_recibido_ui:
                self.on_mensaje_recibido_ui(msg)
            return
        if tipo == "PRESENCIA":
            conectados = msg.get("usuariosConectados") or []
            self.historial.marcar_conectados(conectados)
            for codigo in conectados:
                self._asegurar_en_cache(codigo)
            if self.on_presencia_ui:
                self.on_presencia_ui(msg)
            return
        # SYNC_LOGIN trae el mismo cuerpo que un mensaje: se persiste igual.
        if tipo in ("MENSAJE_TEXTO", "MENSAJE_IMAGEN", "MENSAJE_ARCHIVO",
                    "BROADCAST", "SYNC_LOGIN"):
            # Lo entrante siempre es (remitente → yo): asi el broadcast
            # aparece en la conversacion con su remitente (leccion Fase 5).
            # Las imágenes y archivos NO se guardan en base64 local (§4.2:
            # sin bytes masivos); el preview usa el mensaje vivo y la descarga
            # es bajo demanda con DESCARGAR_ARCHIVO.
            es_imagen = (tipo == "MENSAJE_IMAGEN")
            es_archivo = (tipo == "MENSAJE_ARCHIVO")
            self.historial.guardar_mensaje(
                id_mensaje=msg.get("id"),
                origen=msg.get("remitente"),
                destino=self.codigo_actual,
                tipo=tipo,
                contenido=("[Imagen]" if es_imagen
                           else "[Archivo]" if es_archivo
                           else msg.get("contenido")),
                fecha_envio=msg.get("fechaHora"),
                hash_sha256=msg.get("hashSha256") or "",
                num_caracteres=msg.get("numCaracteres") or 0,
                num_palabras=msg.get("numPalabras") or 0,
                nombre_archivo=msg.get("nombreArchivo") or msg.get("nombre") or "",
                tamano_archivo=msg.get("tamanoArchivo") or msg.get("tamano") or 0,
                descargado=1 if tipo == "MENSAJE_TEXTO" else 0,
                archivo_id=msg.get("archivoId"), estado="ENVIADO",
            )
            if msg.get("remitente"):
                self._asegurar_en_cache(msg.get("remitente"))
            if self.on_mensaje_recibido_ui:
                self.on_mensaje_recibido_ui(msg)


    def _handle_cierre(self, msg):
        self.codigo_actual = None
        if self.on_cierre_ui:
            self.on_cierre_ui(msg)

    def _handle_desconexion(self):
        if self.on_desconexion_ui:
            self.on_desconexion_ui()
