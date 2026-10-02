import base64
import datetime
import os
import uuid
from pathlib import Path

from PySide6.QtCore import QObject, QRunnable, QSize, Qt, QThreadPool, Signal
from PySide6.QtGui import QImage, QPixmap
from PySide6.QtWidgets import (
    QFileDialog, QFrame, QHBoxLayout, QLabel, QListWidgetItem, QMainWindow,
    QMessageBox, QInputDialog, QSplitter, QVBoxLayout, QWidget,
)
from qfluentwidgets import ComboBox, LineEdit, ListWidget, PrimaryPushButton, PushButton


class _UiEvents(QObject):
    message = Signal(object)
    closure = Signal(object)
    presence = Signal(object)
    disconnected = Signal()
    task = Signal(str, object, object)


class _Task(QRunnable):
    def __init__(self, kind, action, events):
        super().__init__()
        self.kind = kind
        self.action = action
        self.events = events

    def run(self):
        try:
            self.events.task.emit(self.kind, self.action(), None)
        except Exception as error:
            self.events.task.emit(self.kind, None, error)


class _MessageRow(QWidget):
    def __init__(self, item, reply, retry, descargar=None, parent=None):
        super().__init__(parent)
        self.item = item
        self.descargar_cb = descargar
        is_own = bool(item.get("is_own") or item.get("sender") == "Tú")
        self.item["is_own"] = is_own

        row_layout = QHBoxLayout(self)
        row_layout.setContentsMargins(10, 3, 10, 3)
        row_layout.setSpacing(0)

        self.bubble = QFrame(self)
        self.bubble.setObjectName("bubble")
        self.bubble.setMaximumWidth(580)
        self.bubble.setMinimumWidth(180)

        if is_own:
            self.bubble.setStyleSheet("""
                QFrame#bubble {
                    background: #E1F5FE;
                    border: 1px solid #B3E5FC;
                    border-radius: 8px;
                }
                QLabel { border: 0; background: transparent; }
            """)
            row_layout.addStretch(1)
            row_layout.addWidget(self.bubble)
        else:
            self.bubble.setStyleSheet("""
                QFrame#bubble {
                    background: #FFFFFF;
                    border: 1px solid #E1E8E9;
                    border-radius: 8px;
                }
                QLabel { border: 0; background: transparent; }
            """)
            row_layout.addWidget(self.bubble)
            row_layout.addStretch(1)

        layout = QVBoxLayout(self.bubble)
        layout.setContentsMargins(12, 8, 12, 8)
        layout.setSpacing(5)

        top = QHBoxLayout()
        top.setSpacing(6)
        self.sender = QLabel(item.get("sender", ""))
        self.sender.setStyleSheet("color: #17635B; font-weight: 700; font-size: 12px;")
        self.status = QLabel("")
        top.addWidget(self.sender, 1)
        top.addWidget(self.status)
        layout.addLayout(top)

        self.body = QLabel(item.get("text", ""))
        self.body.setWordWrap(True)
        self.body.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        self.body.setStyleSheet("color: #24383D; font-size: 13px;")
        self.body.setVisible(bool(item.get("text")))
        layout.addWidget(self.body)

        self.image = QLabel()
        self.image.setAlignment(Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter)
        self.image.setMinimumSize(1, 1)
        self.image.setMaximumSize(420, 280)
        self.image.setVisible(False)
        layout.addWidget(self.image)

        self.image_status = QLabel(item.get("image_state", ""))
        self.image_status.setStyleSheet("color: #61717F; font-size: 11px;")
        layout.addWidget(self.image_status)

        self.retry = PushButton("Reintentar imagen")
        self.retry.setFixedHeight(25)
        self.retry.setVisible(False)
        self.retry.clicked.connect(lambda: retry(self.item))
        layout.addWidget(self.retry, 0, Qt.AlignmentFlag.AlignLeft)

        self.btn_descargar = PushButton("⬇ Descargar")
        self.btn_descargar.setFixedHeight(25)
        es_archivo = bool(item.get("es_archivo"))
        self.btn_descargar.setVisible(es_archivo and bool(item.get("archivo_id")))
        self.btn_descargar.clicked.connect(lambda: self.descargar_cb(self.item) if self.descargar_cb else None)
        layout.addWidget(self.btn_descargar, 0, Qt.AlignmentFlag.AlignLeft)


        footer = QHBoxLayout()
        footer.addWidget(QLabel(item.get("date", "")), 1)
        self.reply = PushButton("Responder")
        self.reply.setFixedHeight(25)
        self.reply.clicked.connect(lambda: reply(self.item))
        footer.addWidget(self.reply)
        layout.addLayout(footer)

        self.actualizar_estado(item.get("state", ""), item.get("raw_state", ""))

    def actualizar_estado(self, visible_state, raw_state=None):
        if not self.item.get("is_own"):
            self.status.setVisible(False)
            return
        visible_state = visible_state or ""
        self.status.setText(visible_state)
        self.status.setVisible(bool(visible_state))
        raw = (raw_state or "").upper()
        if raw == "LEIDO" or "Leído" in visible_state:
            self.status.setStyleSheet("color: #1E88E5; font-size: 11px; font-weight: 700;")
        elif raw == "PENDIENTE" or "Pendiente" in visible_state:
            self.status.setStyleSheet("color: #8A9BA8; font-size: 10px;")
        else:
            self.status.setStyleSheet("color: #61717F; font-size: 10px;")


class ChatWindow(QMainWindow):
    def __init__(self, fachada, codigo, on_logout):
        super().__init__()
        self.fachada = fachada
        self.codigo = codigo
        self.on_logout = on_logout
        self.usuario_seleccionado = None
        self.imagen_pendiente = None
        self._respuesta = ""
        self._cerrando = False
        self._offset = 0
        self._pagina_remota = {}
        self._total_remoto = {}
        self._historial_ids = set()
        self._directorio = []
        self._tareas_imagen = set()
        self._eventos = _UiEvents()
        self._eventos.message.connect(self._procesar_mensaje)
        self._eventos.closure.connect(self._procesar_cierre)
        self._eventos.presence.connect(self._procesar_presencia)
        self._eventos.disconnected.connect(self._procesar_desconexion)
        self._eventos.task.connect(self._procesar_tarea)
        self._pool = QThreadPool(self)
        self._pool.setMaxThreadCount(4)
        self._directorio_imagenes = Path(__file__).resolve().parents[1] / "imagenes"

        self.setWindowTitle(f"Mensajería Académica | {codigo}")
        self.setMinimumSize(QSize(900, 600))
        self.resize(1120, 740)
        self.setAcceptDrops(True)
        self._construir_ui()

        self.fachada.on_mensaje_recibido_ui = self._eventos.message.emit
        self.fachada.on_cierre_ui = self._eventos.closure.emit
        self.fachada.on_presencia_ui = self._eventos.presence.emit
        self.fachada.on_desconexion_ui = self._eventos.disconnected.emit
        self.lista_mensajes.verticalScrollBar().valueChanged.connect(self._cargar_imagenes_visibles)
        self.actualizar_usuarios()

    def _construir_ui(self):
        central = QWidget()
        root = QHBoxLayout(central)
        root.setContentsMargins(0, 0, 0, 0)
        self.setCentralWidget(central)
        splitter = QSplitter(Qt.Orientation.Horizontal)
        root.addWidget(splitter)

        panel_usuarios = QWidget()
        panel_usuarios.setMinimumWidth(245)
        panel_usuarios.setMaximumWidth(350)
        panel_usuarios.setStyleSheet("background: #FFFFFF;")
        left = QVBoxLayout(panel_usuarios)
        left.setContentsMargins(16, 16, 14, 12)
        left.setSpacing(9)
        self.lbl_sesion = QLabel(f"Mensajería\nSesión activa · {self.codigo}")
        self.lbl_sesion.setStyleSheet("font-size: 16px; font-weight: 700; color: #173B46;")
        self.lbl_sesion.setWordWrap(True)
        self.entry_buscar = LineEdit()
        self.entry_buscar.setPlaceholderText("Buscar nombre o código")
        self.entry_buscar.textChanged.connect(self._filtrar_usuarios)
        self.combo_filtro = ComboBox()
        self.combo_filtro.addItems(["Todos", "Conectados"])
        self.combo_filtro.setCurrentIndex(0)
        self.combo_filtro.currentIndexChanged.connect(lambda _i: self._filtrar_usuarios())
        fila_contactos = QHBoxLayout()
        titulo = QLabel("Contactos")
        titulo.setStyleSheet("font-weight: 700; color: #263D43;")
        self.btn_actualizar = PushButton("Actualizar")
        self.btn_actualizar.clicked.connect(self.actualizar_usuarios)
        fila_contactos.addWidget(titulo, 1)
        fila_contactos.addWidget(self.btn_actualizar)
        self.lista_usuarios = ListWidget()
        self.lista_usuarios.currentItemChanged.connect(self._al_seleccionar_usuario)
        self.lbl_estado_red = QLabel("Conectando...")
        self.lbl_estado_red.setStyleSheet("color: #61717F; font-size: 11px;")
        self.lbl_estado_red.setWordWrap(True)
        self.lbl_offline = QLabel("⛔ Sin conexión — mensajes en cola local, se reintentan solos")
        self.lbl_offline.setStyleSheet(
            "color: #B45309; background: #FFFBEB; border: 1px solid #F2CE84;"
            " border-radius: 8px; padding: 8px; font-size: 11px; font-weight: 700;"
        )
        self.lbl_offline.setWordWrap(True)
        self.lbl_offline.setVisible(False)
        self._no_leidos = {}
        left.addWidget(self.lbl_sesion)
        left.addWidget(self.entry_buscar)
        left.addWidget(self.combo_filtro)
        left.addLayout(fila_contactos)
        left.addWidget(self.lista_usuarios, 1)
        left.addWidget(self.lbl_offline)
        left.addWidget(self.lbl_estado_red)
        splitter.addWidget(panel_usuarios)

        panel_chat = QWidget()
        panel_chat.setStyleSheet("background: #F4F7F7;")
        right = QVBoxLayout(panel_chat)
        right.setContentsMargins(0, 0, 0, 0)
        right.setSpacing(0)
        header = QWidget()
        header.setStyleSheet("background: white; border-bottom: 1px solid #DCE4E5;")
        header_row = QHBoxLayout(header)
        header_row.setContentsMargins(18, 12, 18, 12)
        self.lbl_conversacion = QLabel("Selecciona una conversación")
        self.lbl_conversacion.setStyleSheet("font-size: 17px; font-weight: 700; color: #173B46;")
        self.lbl_presencia = QLabel("")
        self.lbl_presencia.setStyleSheet("color: #61717F; font-size: 11px;")
        title_box = QVBoxLayout()
        title_box.setSpacing(2)
        title_box.addWidget(self.lbl_conversacion)
        title_box.addWidget(self.lbl_presencia)
        self.btn_difundir = PushButton("Difundir")
        self.btn_difundir.clicked.connect(self.difundir)
        header_row.addLayout(title_box, 1)
        header_row.addWidget(self.btn_difundir)

        self.lista_mensajes = ListWidget()
        self.lista_mensajes.setSpacing(3)
        self.lista_mensajes.setStyleSheet("QListWidget { background: #F4F7F7; border: 0; padding: 9px; }")
        self.lista_mensajes.setUniformItemSizes(False)
        self.lista_mensajes.setAcceptDrops(False)
        self.btn_anteriores = PushButton("Cargar mensajes anteriores")
        self.btn_anteriores.clicked.connect(self._cargar_anteriores)
        self.btn_anteriores.setVisible(False)

        composer = QWidget()
        composer.setStyleSheet("background: white; border-top: 1px solid #DCE4E5;")
        composer_layout = QVBoxLayout(composer)
        composer_layout.setContentsMargins(14, 8, 14, 12)
        self.lbl_respuesta = QLabel("")
        self.lbl_respuesta.setStyleSheet("color: #147D73; font-size: 11px;")
        self.lbl_respuesta.setWordWrap(True)
        self.lbl_respuesta.setVisible(False)

        self.preview_container = QFrame()
        self.preview_container.setObjectName("previewContainer")
        self.preview_container.setStyleSheet("""
            QFrame#previewContainer {
                background: #F0F4F4;
                border: 1px solid #DCE4E5;
                border-radius: 6px;
            }
        """)
        preview_layout = QHBoxLayout(self.preview_container)
        preview_layout.setContentsMargins(8, 4, 8, 4)
        preview_layout.setSpacing(10)

        self.lbl_preview_thumb = QLabel()
        self.lbl_preview_thumb.setFixedSize(48, 48)
        self.lbl_preview_thumb.setStyleSheet("border-radius: 4px; border: 1px solid #CCD6D8; background: #FFFFFF;")
        self.lbl_preview_thumb.setAlignment(Qt.AlignmentFlag.AlignCenter)

        self.lbl_adjunto = QLabel("")
        self.lbl_adjunto.setStyleSheet("color: #24383D; font-size: 12px; font-weight: 500;")
        self.lbl_adjunto.setWordWrap(True)

        self.btn_quitar_adjunto = PushButton("✕ Quitar")
        self.btn_quitar_adjunto.setFixedSize(75, 26)
        self.btn_quitar_adjunto.clicked.connect(self._quitar_adjunto)

        preview_layout.addWidget(self.lbl_preview_thumb)
        preview_layout.addWidget(self.lbl_adjunto, 1)
        preview_layout.addWidget(self.btn_quitar_adjunto)
        self.preview_container.setVisible(False)

        fila_envio = QHBoxLayout()
        fila_envio.setSpacing(8)
        self.entry_msg = LineEdit()
        self.entry_msg.setPlaceholderText("Escribe un mensaje")
        self.entry_msg.returnPressed.connect(self.enviar)
        self.entry_msg.textChanged.connect(self._actualizar_envio)
        self.btn_imagen = PushButton("Adjuntar")
        self.btn_imagen.clicked.connect(self.adjuntar_imagen)
        self.btn_enviar = PrimaryPushButton("Enviar")
        self.btn_enviar.clicked.connect(self.enviar)
        self.btn_salir = PushButton("Salir")
        self.btn_salir.clicked.connect(self.close)
        self.btn_salir_todos = PushButton("Salir en todos")
        self.btn_salir_todos.clicked.connect(self.salir_en_todos)
        fila_envio.addWidget(self.entry_msg, 1)
        fila_envio.addWidget(self.btn_imagen)
        fila_envio.addWidget(self.btn_enviar)
        composer_layout.addWidget(self.lbl_respuesta)
        composer_layout.addWidget(self.preview_container)
        composer_layout.addLayout(fila_envio)
        bottom = QHBoxLayout()
        bottom.addStretch(1)
        bottom.addWidget(self.btn_salir_todos)
        bottom.addWidget(self.btn_salir)
        composer_layout.addLayout(bottom)

        right.addWidget(header)
        right.addWidget(self.btn_anteriores)
        right.addWidget(self.lista_mensajes, 1)
        right.addWidget(composer)
        splitter.addWidget(panel_chat)
        splitter.setStretchFactor(0, 0)
        splitter.setStretchFactor(1, 1)
        splitter.setSizes([300, 820])
        self._actualizar_envio()

    def _ejecutar(self, tipo, accion):
        self._pool.start(_Task(tipo, accion, self._eventos))

    def actualizar_usuarios(self):
        seleccionado = self.usuario_seleccionado
        def cargar():
            try:
                if hasattr(self.fachada, "listar_usuarios"):
                    usuarios = self.fachada.listar_usuarios()
                else:
                    codigos = self.fachada.listar_conectados()
                    local = {u["codigo"]: u for u in self.fachada.directorio_local()}
                    usuarios = [dict(local.get(c, {"codigo": c}), conectado=1) for c in codigos]
                try:
                    historial = getattr(self.fachada, "historial", None)
                    if historial is not None and hasattr(historial, "contar_no_leidos_por_contacto"):
                        no_leidos = historial.contar_no_leidos_por_contacto(self.codigo)
                    else:
                        no_leidos = {}
                except Exception:
                    no_leidos = {}
                return (usuarios, no_leidos, None)
            except Exception as error:
                try:
                    return (self.fachada.directorio_local(), {}, error)
                except Exception:
                    return ([], {}, error)
        self._ejecutar("directorio", lambda: (seleccionado, *cargar()))

    def _procesar_tarea(self, tipo, resultado, error):
        if self._cerrando:
            return
        if error is not None:
            if tipo == "imagen":
                row = self._row_por_id(getattr(error, "mensaje_id", ""))
                if row is not None:
                    row.item["image_loading"] = False
                    row.image_status.setText(str(error))
                    row.retry.setVisible(True)
            elif tipo == "envio":
                self.btn_enviar.setEnabled(True)
                QMessageBox.critical(self, "No se pudo enviar", str(error))
            elif tipo == "descarga":
                mid = getattr(error, "mensaje_id", "")
                row = self._row_por_id(mid)
                if row and hasattr(row, "btn_descargar"):
                    row.btn_descargar.setEnabled(True)
                    row.btn_descargar.setText("⬇ Descargar")
                QMessageBox.critical(self, "Error al descargar", f"No se pudo descargar el archivo:\n{error}")
            elif tipo == "historial":
                self.lbl_estado_red.setText("No se pudo cargar el historial local")
            elif tipo == "historial_remoto":
                self.lbl_presencia.setText("Mostrando historial local")
            return

        if tipo == "directorio":
            seleccionado, usuarios, no_leidos, error_red = resultado
            self._directorio = [u for u in usuarios if u.get("codigo") != self.codigo]
            self._no_leidos = dict(no_leidos or {})
            self._filtrar_usuarios(seleccionado=seleccionado)
            online = sum(bool(u.get("conectado")) for u in self._directorio)
            sin_red = error_red is not None
            self.lbl_offline.setVisible(sin_red)
            if sin_red:
                self.lbl_estado_red.setText("Sin conexión: directorio local · cola activa (RF-C16)")
                self.lbl_estado_red.setStyleSheet("color: #B45309; font-size: 11px; font-weight: 700;")
            else:
                self.lbl_estado_red.setText(f"{online} contactos en línea")
                self.lbl_estado_red.setStyleSheet("color: #61717F; font-size: 11px;")
        elif tipo == "historial":
            usuario, filas, offset, total = resultado
            if usuario == self.usuario_seleccionado:
                self._pintar_historial(filas, prepend=offset > 0)
                self._offset = offset
                hay_local = offset + 50 < total
                hay_remoto = self._pagina_remota.get(usuario, 0) + 1 < self._total_remoto.get(usuario, 0)
                self.btn_anteriores.setVisible(hay_local or hay_remoto)
                self._cargar_imagenes_visibles()
        elif tipo == "historial_remoto":
            usuario, pagina, total_paginas, older = resultado
            self._pagina_remota[usuario] = pagina
            self._total_remoto[usuario] = total_paginas
            if self.usuario_seleccionado == usuario:
                self._solicitar_historial(usuario, self._offset if older else 0)
        elif tipo == "pagina_anterior":
            usuario, destino, pagina = resultado
            if usuario != self.usuario_seleccionado:
                return
            if destino == "local":
                self._solicitar_historial(usuario, pagina)
            elif destino == "remoto":
                self._solicitar_historial_remoto(usuario, pagina, True)
        elif tipo == "envio":
            usuario, result, ids_enviados = resultado
            self.btn_enviar.setEnabled(True)
            if result is False:
                self.lbl_estado_red.setText("Sin conexión: mensaje guardado para reintento")
            else:
                for mid in ids_enviados:
                    row = self._row_por_id(mid)
                    if row is not None and row.item.get("raw_state") == "PENDIENTE":
                        row.actualizar_estado(self._estado_visible("ENVIADO"), "ENVIADO")
                        row.item["raw_state"] = "ENVIADO"
            self._solicitar_historial(usuario, 0)
        elif tipo == "salir_todos":
            self._cerrando = True
            self.fachada.on_mensaje_recibido_ui = None
            self.fachada.on_cierre_ui = None
            self.fachada.on_presencia_ui = None
            self.fachada.on_desconexion_ui = None
            self.fachada.desconectar()
            self.on_logout()
            self.close()
        elif tipo == "imagen":
            mensaje_id, imagen, ruta = resultado
            item_w, row = self._item_y_row_por_id(mensaje_id)
            if row is not None:
                row.image.setPixmap(QPixmap.fromImage(imagen).scaled(
                    400, 260, Qt.AspectRatioMode.KeepAspectRatio,
                    Qt.TransformationMode.SmoothTransformation))
                row.image.setVisible(True)
                row.item["image_state"] = Path(ruta).name
                row.image_status.setText(row.item["image_state"])
                row.item["image_loaded"] = True
                row.item["image_loading"] = False
                row.retry.setVisible(False)
                if item_w is not None:
                    item_w.setSizeHint(row.sizeHint())
        elif tipo == "descarga":
            mensaje_id, ruta_guardada = resultado
            row = self._row_por_id(mensaje_id)
            if row and hasattr(row, "btn_descargar"):
                row.btn_descargar.setEnabled(True)
                row.btn_descargar.setText("✓ Descargado")
            QMessageBox.information(
                self, "Descarga completada",
                f"Archivo guardado exitosamente en:\n{ruta_guardada}")

    def _filtrar_usuarios(self, text=None, seleccionado=None):
        if seleccionado is None:
            seleccionado = self.usuario_seleccionado
        filtro = (self.entry_buscar.text() or "").strip().casefold()
        solo_conectados = False
        try:
            solo_conectados = self.combo_filtro.currentText() == "Conectados"
        except Exception:
            pass
        usuarios = sorted(self._directorio,
                          key=lambda u: (not bool(u.get("conectado")),
                                         (u.get("apellidos") or "").casefold(), u.get("codigo", "")))
        self.lista_usuarios.clear()
        seleccionado_item = None
        for usuario in usuarios:
            codigo = usuario.get("codigo", "")
            if solo_conectados and not bool(usuario.get("conectado")):
                continue
            nombre = " ".join(filter(None, [usuario.get("nombres"), usuario.get("apellidos")])).strip()
            identidad = f"{nombre} [{codigo}]" if nombre else codigo
            busqueda = f"{identidad} {codigo}".casefold()
            if filtro and filtro not in busqueda:
                continue
            online = bool(usuario.get("conectado"))
            pendientes = 0
            try:
                pendientes = int((self._no_leidos or {}).get(codigo, 0))
            except Exception:
                pendientes = 0
            insignia = f"  ({pendientes})" if pendientes > 0 else ""
            punto = "●" if online else "○"
            item = QListWidgetItem(f"{punto}  {identidad}{insignia}\n     {'en línea' if online else 'desconectado'}")
            item.setData(Qt.ItemDataRole.UserRole, codigo)
            item.setData(Qt.ItemDataRole.UserRole + 1, identidad)
            item.setForeground(Qt.GlobalColor.darkGreen if online else Qt.GlobalColor.gray)
            self.lista_usuarios.addItem(item)
            if codigo == seleccionado:
                seleccionado_item = item
        if seleccionado_item is not None:
            self.lista_usuarios.setCurrentItem(seleccionado_item)

    def _al_seleccionar_usuario(self, actual, anterior=None):
        if actual is None:
            return
        codigo = actual.data(Qt.ItemDataRole.UserRole)
        if not codigo:
            return
        self.usuario_seleccionado = codigo
        try:
            if isinstance(getattr(self, "_no_leidos", None), dict) and codigo in self._no_leidos:
                self._no_leidos.pop(codigo, None)
                self._filtrar_usuarios(seleccionado=codigo)
        except Exception:
            pass
        self.lbl_conversacion.setText(actual.data(Qt.ItemDataRole.UserRole + 1) or codigo)
        self.lbl_presencia.setText("en línea" if any(
            u.get("codigo") == codigo and u.get("conectado") for u in self._directorio)
            else "desconectado")
        self._offset = 0
        self._pagina_remota[codigo] = 0
        self._total_remoto.pop(codigo, None)
        self._historial_ids.clear()
        self.lista_mensajes.clear()
        self._solicitar_historial(codigo, 0)
        if hasattr(self.fachada, "traer_historial"):
            self._solicitar_historial_remoto(codigo, 0, False)
        if hasattr(self.fachada, "marcar_leidos"):
            self._ejecutar("marcar_leidos", lambda: self.fachada.marcar_leidos(codigo))

    def _solicitar_historial(self, usuario, offset):
        def cargar():
            try:
                metodo = self.fachada.obtener_conversacion
                try:
                    filas = metodo(usuario, limit=50, offset=offset)
                except TypeError:
                    filas = metodo(usuario)
                total = self.fachada.contar_conversacion(usuario) if hasattr(
                    self.fachada, "contar_conversacion") else len(filas)
                return usuario, filas, offset, total
            except Exception as error:
                raise RuntimeError(str(error)) from error
        self._ejecutar("historial", cargar)

    def _cargar_anteriores(self):
        usuario = self.usuario_seleccionado
        if usuario is None:
            return
        def consultar():
            total_local = self.fachada.contar_conversacion(usuario)
            if self._offset + 50 < total_local:
                return usuario, "local", self._offset + 50
            siguiente = self._pagina_remota.get(usuario, 0) + 1
            if siguiente < self._total_remoto.get(usuario, 0):
                return usuario, "remoto", siguiente
            return usuario, "ninguno", self._offset
        self._ejecutar("pagina_anterior", consultar)

    def _solicitar_historial_remoto(self, usuario, pagina, older):
        self._ejecutar("historial_remoto", lambda: (
            usuario, pagina, self.fachada.traer_historial(usuario, pagina), older))

    def _pintar_historial(self, filas, prepend=False):
        ordenados = list(reversed(filas))
        anterior = self.lista_mensajes.verticalScrollBar().value()
        for fila in (reversed(ordenados) if prepend else ordenados):
            id_mensaje = fila.get("id_mensaje") or fila.get("id") or ""
            if id_mensaje and id_mensaje in self._historial_ids:
                existing = self._row_por_id(id_mensaje)
                if existing is not None:
                    raw_state = fila.get("estado", "")
                    if existing.item.get("is_own"):
                        existing.actualizar_estado(self._estado_visible(raw_state), raw_state)
                        existing.item["raw_state"] = raw_state
                    if fila.get("archivo_id"):
                        existing.item["archivo_id"] = fila["archivo_id"]
                continue
            if id_mensaje:
                self._historial_ids.add(id_mensaje)
            origen = fila.get("origen") or ""
            usuario = next((u for u in self._directorio if u.get("codigo") == origen), {})
            nombre = " ".join(filter(None, [usuario.get("nombres"), usuario.get("apellidos")])).strip()
            is_own = (origen == self.codigo)
            remitente = "Tú" if is_own else (f"{nombre} [{origen}]" if nombre else origen)
            tipo = fila.get("tipo")
            es_imagen = tipo == "MENSAJE_IMAGEN"
            es_archivo = tipo == "MENSAJE_ARCHIVO"
            raw_state = fila.get("estado", "")
            nombre_arch = fila.get("nombre_archivo") or ""
            tamano_bytes = fila.get("tamano_archivo") or 0
            if tamano_bytes >= 1024 * 1024:
                tamano_str = f"{tamano_bytes/1024/1024:.1f} MB"
            elif tamano_bytes > 0:
                tamano_str = f"{tamano_bytes/1024:.1f} KB"
            else:
                tamano_str = ""
            if es_archivo:
                texto_burbuja = f"📎 {nombre_arch}  ({tamano_str})" if nombre_arch else "[Archivo]"
            elif es_imagen:
                texto_burbuja = ""
            else:
                texto_burbuja = fila.get("contenido") or ""
            item = {
                "id": id_mensaje, "sender": remitente, "date": fila.get("fecha_envio", ""),
                "text": texto_burbuja,
                "state": self._estado_visible(raw_state) if is_own else "",
                "raw_state": raw_state if is_own else "",
                "is_own": is_own,
                "is_image": es_imagen, "es_archivo": es_archivo,
                "archivo_id": fila.get("archivo_id"),
                "filename": nombre_arch, "path": fila.get("ruta_archivo") or "",
                "image_loaded": False, "image_loading": False,
                "image_state": "Imagen" if es_imagen else ""
            }
            self._agregar_fila(item, prepend=prepend)

        if not prepend:
            self.lista_mensajes.scrollToBottom()
        else:
            self.lista_mensajes.verticalScrollBar().setValue(anterior + 50)

    def _agregar_fila(self, mensaje, prepend=False):
        item = QListWidgetItem()
        item.setData(Qt.ItemDataRole.UserRole, mensaje)
        row = _MessageRow(mensaje, self._responder, self._reintentar_imagen, self._descargar_adjunto, self.lista_mensajes)
        if prepend:
            self.lista_mensajes.insertItem(0, item)
        else:
            self.lista_mensajes.addItem(item)

        self.lista_mensajes.setItemWidget(item, row)
        hint = row.sizeHint()
        item.setSizeHint(QSize(hint.width(), max(hint.height(), 70)))
        self._cargar_imagenes_visibles()

    def _responder(self, mensaje):
        extracto = mensaje.get("filename") if mensaje.get("is_image") else mensaje.get("text", "")
        extracto = (extracto or "mensaje")[:120]
        self._respuesta = f'Respuesta a {mensaje.get("sender")}: "{extracto}"'
        self.lbl_respuesta.setText(self._respuesta)
        self.lbl_respuesta.setVisible(True)
        if self.entry_msg.text().strip():
            self.entry_msg.setText(self._respuesta + " · " + self.entry_msg.text().strip())
        else:
            self.entry_msg.setText(self._respuesta)
        self.entry_msg.setFocus()

    def _actualizar_envio(self):
        self.btn_enviar.setEnabled(bool(self.usuario_seleccionado)
                                   and bool(self.entry_msg.text().strip() or self.imagen_pendiente))

    def enviar(self):
        texto = self.entry_msg.text().strip()
        usuario = self.usuario_seleccionado
        ruta = self.imagen_pendiente
        if not usuario or (not texto and not ruta):
            return
        self.btn_enviar.setEnabled(False)

        ahora = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        id_texto = str(uuid.uuid4()) if texto else None
        id_imagen = str(uuid.uuid4()) if ruta else None

        # Reflejo optimista inmediato en la UI con estado PENDIENTE
        if id_texto:
            item_texto = {
                "id": id_texto, "sender": "Tú", "date": ahora,
                "text": texto, "state": self._estado_visible("PENDIENTE"),
                "raw_state": "PENDIENTE", "is_own": True,
                "is_image": False, "archivo_id": None,
                "filename": "", "path": "", "image_loaded": False,
                "image_loading": False, "image_state": ""
            }
            self._historial_ids.add(id_texto)
            self._agregar_fila(item_texto)

        if id_imagen:
            nombre_adj = Path(ruta).name
            ext_adj = Path(ruta).suffix.casefold()
            es_imagen_adj = ext_adj in self._EXTENSIONES_IMAGEN
            tamano_kb = Path(ruta).stat().st_size / 1024
            tamano_str = (f"{tamano_kb/1024:.1f} MB" if tamano_kb >= 1024
                          else f"{tamano_kb:.1f} KB")
            item_imagen = {
                "id": id_imagen, "sender": "Tú", "date": ahora,
                "text": "" if es_imagen_adj else f"📎 {nombre_adj}  ({tamano_str})",
                "state": self._estado_visible("PENDIENTE"),
                "raw_state": "PENDIENTE", "is_own": True,
                "is_image": es_imagen_adj, "archivo_id": None,
                "filename": nombre_adj, "path": ruta,
                "image_loaded": False, "image_loading": False,
                "image_state": nombre_adj if es_imagen_adj else ""
            }
            self._historial_ids.add(id_imagen)
            self._agregar_fila(item_imagen)

        self.lista_mensajes.scrollToBottom()

        # Limpiar entrada y adjunto inmediatamente
        self.entry_msg.clear()
        self._respuesta = ""
        self.lbl_respuesta.clear()
        self.lbl_respuesta.setVisible(False)
        self._quitar_adjunto()

        def enviar_ambos():
            en_red = True
            ids_enviados = []
            if id_texto:
                try:
                    ok = self.fachada.enviar_texto(usuario, texto, id_msg=id_texto)
                except TypeError:
                    ok = self.fachada.enviar_texto(usuario, texto)
                en_red = (ok is not False) and en_red
                ids_enviados.append(id_texto)
            if id_imagen:
                ext = Path(ruta).suffix.casefold()
                if ext in self._EXTENSIONES_IMAGEN:
                    try:
                        ok = self.fachada.enviar_imagen(usuario, ruta, id_msg=id_imagen)
                    except TypeError:
                        ok = self.fachada.enviar_imagen(usuario, ruta)
                else:
                    try:
                        ok = self.fachada.enviar_archivo(usuario, ruta, id_msg=id_imagen)
                    except TypeError:
                        ok = self.fachada.enviar_archivo(usuario, ruta)
                en_red = (ok is not False) and en_red
                ids_enviados.append(id_imagen)
            return usuario, en_red, ids_enviados

        self._ejecutar("envio", enviar_ambos)

    _EXTENSIONES_IMAGEN = {".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp"}

    def _fijar_imagen_adjunta(self, ruta):
        if not ruta or not Path(ruta).is_file():
            return
        self.imagen_pendiente = str(Path(ruta).resolve())
        ext = Path(ruta).suffix.casefold()
        es_imagen = ext in self._EXTENSIONES_IMAGEN
        if es_imagen:
            pixmap = QPixmap(self.imagen_pendiente)
            if not pixmap.isNull():
                thumb = pixmap.scaled(48, 48, Qt.AspectRatioMode.KeepAspectRatio,
                                      Qt.TransformationMode.SmoothTransformation)
                self.lbl_preview_thumb.setPixmap(thumb)
            else:
                self.lbl_preview_thumb.setText("🖼")
        else:
            # Ícono genérico por extensión
            iconos = {
                ".pdf": "📄", ".docx": "📝", ".xlsx": "📊",
                ".pptx": "📑", ".txt": "📃", ".zip": "🗜", ".rar": "🗜",
            }
            self.lbl_preview_thumb.setText(iconos.get(ext, "📎"))
            self.lbl_preview_thumb.setStyleSheet(
                "border-radius: 4px; border: 1px solid #CCD6D8; "
                "background: #FFFFFF; font-size: 24px;")
        tamano_kb = Path(ruta).stat().st_size / 1024
        tamano_str = (f"{tamano_kb/1024:.1f} MB" if tamano_kb >= 1024
                      else f"{tamano_kb:.1f} KB")
        self.lbl_adjunto.setText(f"{Path(ruta).name}  ({tamano_str})")
        self.preview_container.setVisible(True)
        self._actualizar_envio()

    def _quitar_adjunto(self):
        self.imagen_pendiente = None
        self.lbl_preview_thumb.clear()
        self.lbl_adjunto.clear()
        self.preview_container.setVisible(False)
        self._actualizar_envio()

    def adjuntar_imagen(self):
        """Adjunta cualquier archivo (imagen o documento) a la conversación."""
        if not self.usuario_seleccionado:
            QMessageBox.information(self, "Seleccionar conversación",
                                    "Selecciona una conversación primero.")
            return
        ruta, _ = QFileDialog.getOpenFileName(
            self, "Seleccionar archivo", "",
            "Todos los archivos (*.*);;"
            "Imágenes (*.png *.jpg *.jpeg *.gif *.bmp *.webp);;"
            "Documentos (*.pdf *.docx *.xlsx *.pptx *.txt *.zip)")
        if ruta:
            self._fijar_imagen_adjunta(ruta)

    def dragEnterEvent(self, event):
        if event.mimeData().hasUrls():
            for url in event.mimeData().urls():
                if Path(url.toLocalFile()).is_file():
                    event.acceptProposedAction()
                    return
        event.ignore()

    def dragMoveEvent(self, event):
        if event.mimeData().hasUrls():
            for url in event.mimeData().urls():
                if Path(url.toLocalFile()).is_file():
                    event.acceptProposedAction()
                    return
        event.ignore()

    def dropEvent(self, event):
        if event.mimeData().hasUrls():
            for url in event.mimeData().urls():
                ruta = url.toLocalFile()
                if Path(ruta).is_file():
                    if not self.usuario_seleccionado:
                        QMessageBox.information(
                            self, "Seleccionar conversación",
                            "Selecciona una conversación primero para adjuntar el archivo.")
                        event.acceptProposedAction()
                        return
                    self._fijar_imagen_adjunta(ruta)
                    event.acceptProposedAction()
                    return
        event.ignore()

    def difundir(self):
        texto, aceptado = QInputDialog.getText(self, "Difundir", "Mensaje para todos:")
        if aceptado and texto.strip():
            self._ejecutar("difusion", lambda: self.fachada.difundir(texto.strip()))

    def salir_en_todos(self):
        respuesta = QMessageBox.question(
            self, "Confirmar salida", "¿Cerrar todas las sesiones de esta cuenta?",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
            QMessageBox.StandardButton.No)
        if respuesta != QMessageBox.StandardButton.Yes:
            return
        self._ejecutar("salir_todos", self.fachada.expulsar_otras_sesiones)

    def _procesar_mensaje(self, mensaje):
        tipo = mensaje.get("tipo")
        if tipo in ("MENSAJE_ENTREGADO", "MENSAJE_LEIDO"):
            id_mensaje = mensaje.get("contenido")
            if id_mensaje:
                nuevo_estado = "LEIDO" if tipo == "MENSAJE_LEIDO" else "ENTREGADO"
                row = self._row_por_id(id_mensaje)
                if row is not None and row.item.get("is_own"):
                    row.actualizar_estado(self._estado_visible(nuevo_estado), nuevo_estado)
                    row.item["raw_state"] = nuevo_estado
            if self.usuario_seleccionado:
                self._solicitar_historial(self.usuario_seleccionado, 0)
            return
        if tipo == "PRESENCIA":
            return
        remitente = mensaje.get("remitente")
        if remitente == self.usuario_seleccionado:
            id_mensaje = mensaje.get("id") or str(uuid.uuid4())
            if id_mensaje not in self._historial_ids:
                usuario = next((u for u in self._directorio if u.get("codigo") == remitente), {})
                nombre = " ".join(filter(None, [usuario.get("nombres"), usuario.get("apellidos")])).strip()
                identidad = f"{nombre} [{remitente}]" if nombre else remitente
                es_imagen = tipo == "MENSAJE_IMAGEN"
                item = {
                    "id": id_mensaje, "sender": identidad, "date": mensaje.get("fechaHora", ""),
                    "text": "" if es_imagen else (mensaje.get("contenido") or ""),
                    "state": "", "raw_state": "", "is_own": False,
                    "is_image": es_imagen,
                    "archivo_id": mensaje.get("archivoId"),
                    "filename": mensaje.get("nombreArchivo") or "",
                    "path": "", "image_loaded": False, "image_loading": False,
                    "image_state": "Imagen", "image_base64": mensaje.get("contenidoImagen")
                }
                self._historial_ids.add(id_mensaje)
                self._agregar_fila(item)
                self.lista_mensajes.scrollToBottom()
            self._solicitar_historial(remitente, 0)
            if hasattr(self.fachada, "marcar_leidos"):
                self._ejecutar("marcar_leidos", lambda: self.fachada.marcar_leidos(remitente))

    def _procesar_presencia(self, mensaje):
        self.actualizar_usuarios()

    def _procesar_desconexion(self):
        self.lbl_estado_red.setText("Conexión cerrada")

    def _cargar_imagenes_visibles(self, *_):
        if not self.isVisible() or self.lista_mensajes.count() == 0:
            return
        viewport = self.lista_mensajes.viewport().rect()
        for indice in range(self.lista_mensajes.count()):
            item = self.lista_mensajes.item(indice)
            rect = self.lista_mensajes.visualItemRect(item)
            if not rect.isValid() or not viewport.intersects(rect):
                continue
            mensaje = item.data(Qt.ItemDataRole.UserRole)
            if not mensaje.get("is_image") or mensaje.get("image_loading") or mensaje.get("image_loaded"):
                continue
            mensaje["image_loading"] = True
            row = self.lista_mensajes.itemWidget(item)
            if row:
                row.image_status.setText("Cargando imagen…")
            self._tareas_imagen.add(mensaje.get("id"))
            def cargar(m=mensaje):
                try:
                    return self._cargar_imagen(m)
                except Exception as error:
                    error.mensaje_id = m.get("id")
                    raise
            self._ejecutar("imagen", cargar)

    def _cargar_imagen(self, mensaje):
        carpeta = self._directorio_imagenes
        carpeta.mkdir(parents=True, exist_ok=True)
        extension = Path(mensaje.get("filename") or "").suffix or ".img"
        ruta = mensaje.get("path")
        if not ruta or not Path(ruta).is_file():
            ruta = str(carpeta / ((mensaje.get("id") or "imagen") + extension))
            imagen_b64 = mensaje.get("image_base64")
            if imagen_b64:
                Path(ruta).write_bytes(base64.b64decode(imagen_b64))
            else:
                self.fachada.obtener_imagen(mensaje["id"], ruta)
        imagen = QImage(ruta)
        if imagen.isNull():
            raise RuntimeError("El archivo descargado no es una imagen válida")
        return mensaje.get("id"), imagen, ruta

    def _item_y_row_por_id(self, mensaje_id):
        for index in range(self.lista_mensajes.count()):
            item = self.lista_mensajes.item(index)
            mensaje = item.data(Qt.ItemDataRole.UserRole)
            if mensaje and mensaje.get("id") == mensaje_id:
                return item, self.lista_mensajes.itemWidget(item)
        return None, None

    def _row_por_id(self, mensaje_id):
        _, row = self._item_y_row_por_id(mensaje_id)
        return row

    def _reintentar_imagen(self, mensaje):
        mensaje["image_loading"] = True
        row = self._row_por_id(mensaje.get("id"))
        if row is not None:
            row.retry.setVisible(False)
            row.image_status.setText("Reintentando…")
        def cargar():
            try:
                return self._cargar_imagen(mensaje)
            except Exception as error:
                error.mensaje_id = mensaje.get("id")
                raise
            self._ejecutar("imagen", cargar)

    def _descargar_adjunto(self, mensaje):
        archivo_id = mensaje.get("archivo_id")
        if not archivo_id:
            QMessageBox.warning(self, "Descargar", "El identificador del archivo no está disponible.")
            return
        nombre_defecto = mensaje.get("filename") or "archivo.bin"
        ruta_sugerida, _ = QFileDialog.getSaveFileName(
            self, "Guardar archivo como", nombre_defecto, "Todos los archivos (*.*)")
        if not ruta_sugerida:
            return

        row = self._row_por_id(mensaje.get("id"))
        if row and hasattr(row, "btn_descargar"):
            row.btn_descargar.setEnabled(False)
            row.btn_descargar.setText("Descargando…")

        def descargar():
            try:
                self.fachada.descargar_archivo(archivo_id, ruta_sugerida)
                return mensaje.get("id"), ruta_sugerida
            except Exception as error:
                error.mensaje_id = mensaje.get("id")
                raise

        self._ejecutar("descarga", descargar)

    @staticmethod
    def _estado_visible(estado):
        return {
            "PENDIENTE": "⏳ Pendiente",
            "ENVIADO": "✓ Enviado",
            "ENTREGADO": "✓✓ Entregado",
            "LEIDO": "✓✓ Leído",
        }.get(estado, estado or "")

    def _procesar_cierre(self, mensaje):
        motivo = mensaje.get("motivo") or mensaje.get("mensajeError") or "Sesión cerrada por el servidor."
        QMessageBox.information(self, "Sesión cerrada", motivo)
        self._cerrando = True
        self.fachada.desconectar()
        self.on_logout()
        self.close()


    def closeEvent(self, evento):
        if self._cerrando:
            evento.accept()
            return
        respuesta = QMessageBox.question(
            self, "Confirmar salida", "¿Deseas cerrar esta sesión y volver al inicio?",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
            QMessageBox.StandardButton.No)
        if respuesta != QMessageBox.StandardButton.Yes:
            evento.ignore()
            return
        self._cerrando = True
        self.fachada.on_mensaje_recibido_ui = None
        self.fachada.on_cierre_ui = None
        self.fachada.on_presencia_ui = None
        self.fachada.on_desconexion_ui = None
        self.fachada.desconectar()
        self.on_logout()
        evento.accept()
