package universidad.mensajeria.cliente.presentacion;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

/** Login: solo delega en FachadaCliente. */
public final class LoginController {

    @FXML
    private TextField campoCodigo;
    @FXML
    private PasswordField campoContrasena;
    @FXML
    private Label etiquetaError;

    @FXML
    private void alEntrar() {
        try {
            ClienteApp.fachada().login(
                    campoCodigo.getText().trim(), campoContrasena.getText());
            ClienteApp.mostrarChat();
        } catch (Exception e) {
            etiquetaError.setText(fallo(e));
        }
    }

    // Fase 10.2 (P2): sin «Crear cuenta». La alta de usuarios es solo por
    // archivo plano (usuarios_iniciales.csv); RegistroView queda inalcanzable.

    private static String fallo(Exception e) {
        return e.getMessage() != null ? e.getMessage() : "no se pudo entrar";
    }
}
