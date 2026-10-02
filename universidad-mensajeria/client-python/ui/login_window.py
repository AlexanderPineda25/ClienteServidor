from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import QLabel, QVBoxLayout, QWidget
from qfluentwidgets import LineEdit, PasswordLineEdit, PrimaryPushButton


class LoginWindow(QWidget):
    login_succeeded = Signal(str)

    def __init__(self, fachada):
        super().__init__()
        self.fachada = fachada
        self.setWindowTitle("Mensajeria Academica | Iniciar sesion")
        self.setFixedSize(420, 390)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(38, 34, 38, 30)
        layout.setSpacing(12)

        eyebrow = QLabel("UNIVERSIDAD")
        eyebrow.setStyleSheet("color: #147D73; font-size: 11px; font-weight: 700;")
        title = QLabel("Mensajeria Academica")
        title.setStyleSheet("font-size: 23px; font-weight: 700; color: #183247;")
        subtitle = QLabel("Inicia sesion con tus credenciales institucionales.")
        subtitle.setStyleSheet("color: #61717F;")
        subtitle.setWordWrap(True)

        self.entry_codigo = LineEdit()
        self.entry_codigo.setPlaceholderText("Codigo institucional")
        self.entry_codigo.setClearButtonEnabled(True)
        self.entry_password = PasswordLineEdit()
        self.entry_password.setPlaceholderText("Contrasena")
        self.entry_password.returnPressed.connect(self.login)

        self.btn_login = PrimaryPushButton("Entrar")
        self.btn_login.setMinimumHeight(38)
        self.btn_login.clicked.connect(self.login)
        self.lbl_error = QLabel("")
        self.lbl_error.setStyleSheet("color: #B42318;")
        self.lbl_error.setWordWrap(True)

        layout.addWidget(eyebrow)
        layout.addWidget(title)
        layout.addWidget(subtitle)
        layout.addSpacing(16)
        layout.addWidget(QLabel("Codigo"))
        layout.addWidget(self.entry_codigo)
        layout.addWidget(QLabel("Contrasena"))
        layout.addWidget(self.entry_password)
        layout.addSpacing(4)
        layout.addWidget(self.btn_login)
        layout.addWidget(self.lbl_error)
        layout.addStretch(1)

        self.entry_codigo.setFocus(Qt.FocusReason.OtherFocusReason)

    def login(self):
        codigo = self.entry_codigo.text().strip()
        contrasena = self.entry_password.text()
        if not codigo or not contrasena:
            self.lbl_error.setText("Ingresa el codigo y la contrasena.")
            return

        self.btn_login.setEnabled(False)
        self.lbl_error.clear()
        try:
            respuesta = self.fachada.login(codigo, contrasena)
            if respuesta.get("exito"):
                self.login_succeeded.emit(codigo)
            else:
                self.lbl_error.setText(
                    respuesta.get("mensajeError", "No fue posible validar las credenciales.")
                )
        except Exception as error:
            self.lbl_error.setText(f"No se pudo conectar: {error}")
        finally:
            self.btn_login.setEnabled(True)
