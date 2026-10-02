from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import QCheckBox, QHBoxLayout, QLabel, QVBoxLayout, QWidget
from qfluentwidgets import LineEdit, PasswordLineEdit, PrimaryPushButton, SpinBox


class LoginWindow(QWidget):
    login_succeeded = Signal(str)

    def __init__(self, fachada, aviso=""):
        super().__init__()
        self.fachada = fachada
        self.setWindowTitle("Mensajeria Academica | Iniciar sesion")
        self.setMinimumSize(440, 560)
        self.setStyleSheet("background: #EEF1F6;")

        root = QVBoxLayout(self)
        root.setContentsMargins(24, 24, 24, 24)
        card = QWidget()
        card.setStyleSheet(
            "background: white; border: 1px solid #D7DEE8; border-radius: 12px;"
        )
        layout = QVBoxLayout(card)
        layout.setContentsMargins(28, 24, 28, 24)
        layout.setSpacing(10)

        header = QWidget()
        header.setStyleSheet("background: #1F3A5F; border-radius: 8px;")
        header_layout = QVBoxLayout(header)
        header_layout.setContentsMargins(16, 14, 16, 14)
        eyebrow = QLabel("UNIVERSIDAD")
        eyebrow.setStyleSheet("color: #9FB6D8; font-size: 11px; font-weight: 700; background: transparent;")
        title = QLabel("Mensajeria Academica")
        title.setStyleSheet("font-size: 20px; font-weight: 700; color: white; background: transparent;")
        subtitle = QLabel("Iniciar sesión · Solo autenticación, sin Registrarse")
        subtitle.setStyleSheet("color: #C9D6E8; font-size: 12px; background: transparent;")
        subtitle.setWordWrap(True)
        header_layout.addWidget(eyebrow)
        header_layout.addWidget(title)
        header_layout.addWidget(subtitle)
        layout.addWidget(header)

        nota = QLabel("El alta de usuarios la hace el administrador en el servidor.")
        nota.setStyleSheet("color: #64748B; font-size: 11px; font-style: italic;")
        nota.setWordWrap(True)
        layout.addWidget(nota)

        layout.addWidget(QLabel("Código universitario"))
        self.entry_codigo = LineEdit()
        self.entry_codigo.setPlaceholderText("A001234567")
        self.entry_codigo.setClearButtonEnabled(True)
        layout.addWidget(self.entry_codigo)

        layout.addWidget(QLabel("Contraseña"))
        self.entry_password = PasswordLineEdit()
        self.entry_password.setPlaceholderText("••••••••••")
        self.entry_password.returnPressed.connect(self.login)
        layout.addWidget(self.entry_password)

        fila_server = QHBoxLayout()
        fila_server.setSpacing(10)
        host_box = QVBoxLayout()
        host_box.addWidget(QLabel("Servidor"))
        self.entry_host = LineEdit()
        config = getattr(fachada, "config", None)
        host_inicial = "localhost"
        puerto_inicial = 5000
        try:
            if config is not None:
                host_inicial = config.get("host", host_inicial)
                puerto_inicial = int(config.get("puerto", puerto_inicial))
        except (TypeError, ValueError):
            pass
        self.entry_host.setText(str(host_inicial))
        self.entry_host.setClearButtonEnabled(True)
        host_box.addWidget(self.entry_host)
        puerto_box = QVBoxLayout()
        puerto_box.addWidget(QLabel("Puerto"))
        self.spin_puerto = SpinBox()
        self.spin_puerto.setRange(1, 65535)
        self.spin_puerto.setValue(puerto_inicial)
        puerto_box.addWidget(self.spin_puerto)
        fila_server.addLayout(host_box, 2)
        fila_server.addLayout(puerto_box, 1)
        layout.addLayout(fila_server)

        try:
            actual = f"{host_inicial}:{puerto_inicial}"
        except Exception:
            actual = "localhost:5000"
        self.lbl_servidor = QLabel(f"Servidor actual: {actual} · se configura en client.properties.")
        self.lbl_servidor.setStyleSheet("color: #475569; font-size: 11px;")
        self.lbl_servidor.setWordWrap(True)
        layout.addWidget(self.lbl_servidor)

        self.check_recordar = QCheckBox("Recordar servidor")
        self.check_recordar.setChecked(True)
        layout.addWidget(self.check_recordar)

        self.btn_login = PrimaryPushButton("Iniciar sesión →")
        self.btn_login.setMinimumHeight(40)
        self.btn_login.clicked.connect(self.login)
        layout.addWidget(self.btn_login)

        self.lbl_error = QLabel("")
        self.lbl_error.setStyleSheet(
            "color: #B91C1C; background: #FDECEA; border: 1px solid #F1A9A5;"
            " border-radius: 8px; padding: 8px;"
        )
        self.lbl_error.setWordWrap(True)
        self.lbl_error.setVisible(False)
        layout.addWidget(self.lbl_error)

        self.lbl_aviso = QLabel("")
        self.lbl_aviso.setStyleSheet(
            "color: #B45309; background: #FFFBEB; border: 1px solid #F2CE84;"
            " border-radius: 8px; padding: 8px;"
        )
        self.lbl_aviso.setWordWrap(True)
        self.lbl_aviso.setVisible(bool(aviso))
        if aviso:
            self.lbl_aviso.setText(f"⏳ {aviso}")
        layout.addWidget(self.lbl_aviso)

        root.addWidget(card)
        self.entry_codigo.setFocus(Qt.FocusReason.OtherFocusReason)
        self.entry_codigo.textChanged.connect(self._actualizar_boton)
        self.entry_password.textChanged.connect(self._actualizar_boton)
        self._actualizar_boton()

    def _actualizar_boton(self):
        self.btn_login.setEnabled(bool(self.entry_codigo.text().strip()
                                        and self.entry_password.text()))

    def mostrar_aviso(self, texto):
        self.lbl_aviso.setText(f"⏳ {texto}")
        self.lbl_aviso.setVisible(True)

    def login(self):
        codigo = self.entry_codigo.text().strip()
        contrasena = self.entry_password.text()
        if not codigo or not contrasena:
            self.lbl_error.setText("✖ Ingresa el codigo y la contrasena.")
            self.lbl_error.setVisible(True)
            return

        self.btn_login.setEnabled(False)
        self.btn_login.setText("Conectando…")
        self.lbl_error.setVisible(False)
        try:
            if self.check_recordar.isChecked():
                config = getattr(self.fachada, "config", None)
                if config is not None and hasattr(config, "set"):
                    config.set("host", self.entry_host.text().strip() or "localhost")
                    config.set("puerto", self.spin_puerto.value())
                    if hasattr(config, "save"):
                        config.save()
            respuesta = self.fachada.login(codigo, contrasena)
            if respuesta.get("exito"):
                self.login_succeeded.emit(codigo)
            else:
                self.lbl_error.setText(
                    f"✖ Usuario no registrado o datos incorrectos: "
                    f"{respuesta.get('mensajeError', 'verifica el código y la contraseña')}"
                )
                self.lbl_error.setVisible(True)
        except Exception as error:
            self.lbl_error.setText(f"✖ No se pudo conectar: {error}")
            self.lbl_error.setVisible(True)
        finally:
            self.btn_login.setEnabled(True)
            self.btn_login.setText("Iniciar sesión →")
            self._actualizar_boton()
