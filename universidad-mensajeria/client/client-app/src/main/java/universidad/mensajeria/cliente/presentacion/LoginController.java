package universidad.mensajeria.cliente.presentacion;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import universidad.mensajeria.cliente.transversal.config.ClienteConfig;

/** Login mockup §2: solo autenticacion, host/puerto visibles, spinner y avisos. */
public final class LoginController {

    @FXML
    private TextField campoCodigo;
    @FXML
    private PasswordField campoContrasena;
    @FXML
    private TextField campoHost;
    @FXML
    private TextField campoPuerto;
    @FXML
    private Label etiquetaServidor;
    @FXML
    private CheckBox checkRecordar;
    @FXML
    private Button botonEntrar;
    @FXML
    private ProgressIndicator spinner;
    @FXML
    private Label etiquetaError;
    @FXML
    private Label etiquetaAviso;

    @FXML
    private void initialize() {
        try {
            ClienteConfig config = ClienteConfig.cargar();
            campoHost.setText(config.host());
            campoPuerto.setText(String.valueOf(config.puerto()));
            etiquetaServidor.setText("Servidor actual: " + config.host() + ":" + config.puerto()
                    + " · se configura en client.properties (§5.2).");
        } catch (Exception e) {
            etiquetaServidor.setText("Servidor: localhost:5000 (client.properties no legible).");
            campoHost.setText("localhost");
            campoPuerto.setText("5000");
        }
        String aviso = ClienteApp.consumirAviso();
        if (aviso != null && !aviso.isBlank()) {
            mostrarAviso(aviso);
        }
        Runnable refrescar = this::actualizarBoton;
        campoCodigo.textProperty().addListener((o, a, b) -> refrescar.run());
        campoContrasena.textProperty().addListener((o, a, b) -> refrescar.run());
        actualizarBoton();
        Platform.runLater(() -> campoCodigo.requestFocus());
    }

    private void actualizarBoton() {
        boolean listo = campoCodigo.getText() != null && !campoCodigo.getText().isBlank()
                && campoContrasena.getText() != null && !campoContrasena.getText().isEmpty();
        botonEntrar.setDisable(!listo || spinner.isVisible());
    }

    @FXML
    private void alEntrar() {
        String codigo = campoCodigo.getText() == null ? "" : campoCodigo.getText().trim();
        String clave = campoContrasena.getText() == null ? "" : campoContrasena.getText();
        if (codigo.isEmpty() || clave.isEmpty()) {
            mostrarError("Ingresa el código y la contraseña.");
            return;
        }
        ocultarMensajes();
        botonEntrar.setDisable(true);
        spinner.setVisible(true);
        spinner.setManaged(true);

        Task<Void> tarea = new Task<>() {
            @Override
            protected Void call() throws Exception {
                ClienteApp.fachada().login(codigo, clave);
                return null;
            }
        };
        tarea.setOnSucceeded(e -> {
            guardarRecordarSiToca();
            Platform.runLater(() -> {
                spinner.setVisible(false);
                spinner.setManaged(false);
                botonEntrar.setDisable(false);
            });
            try {
                ClienteApp.mostrarChat();
            } catch (Exception ex) {
                mostrarError(fallo(ex));
            }
        });
        tarea.setOnFailed(e -> {
            Throwable causa = tarea.getException();
            Platform.runLater(() -> {
                spinner.setVisible(false);
                spinner.setManaged(false);
                botonEntrar.setDisable(false);
                mostrarError("Usuario no registrado o datos incorrectos"
                        + (causa != null && causa.getMessage() != null ? ": " + causa.getMessage() : "")
                        + " (LOGIN_RESPUESTA exito=false).");
            });
        });
        new Thread(tarea, "login-fondo").start();
    }

    private void guardarRecordarSiToca() {
        if (checkRecordar == null || !checkRecordar.isSelected()) {
            return;
        }
        try {
            String host = campoHost.getText() == null ? "" : campoHost.getText().trim();
            String puerto = campoPuerto.getText() == null ? "" : campoPuerto.getText().trim();
            if (host.isEmpty() || puerto.isEmpty()) {
                return;
            }
            int p = Integer.parseInt(puerto);
            if (p <= 0 || p > 65535) {
                return;
            }
            java.util.Properties props = new java.util.Properties();
            java.nio.file.Path externo = java.nio.file.Path.of("client.properties");
            if (java.nio.file.Files.exists(externo)) {
                try (java.io.Reader r = java.nio.file.Files.newBufferedReader(externo,
                        java.nio.charset.StandardCharsets.UTF_8)) {
                    props.load(r);
                }
            }
            props.setProperty("host", host);
            props.setProperty("puerto", String.valueOf(p));
            try (java.io.Writer w = java.nio.file.Files.newBufferedWriter(externo,
                    java.nio.charset.StandardCharsets.UTF_8)) {
                props.store(w, "Recordar servidor (mockup login)");
            }
        } catch (Exception ignorada) {
            // recordar es best-effort, nunca bloquea el login
        }
    }

    private void mostrarError(String texto) {
        etiquetaError.setText("✖ " + texto);
        etiquetaError.setVisible(true);
        etiquetaError.setManaged(true);
    }

    private void mostrarAviso(String texto) {
        etiquetaAviso.setText("⏳ " + texto);
        etiquetaAviso.setVisible(true);
        etiquetaAviso.setManaged(true);
    }

    private void ocultarMensajes() {
        etiquetaError.setVisible(false);
        etiquetaError.setManaged(false);
        etiquetaAviso.setVisible(false);
        etiquetaAviso.setManaged(false);
    }

    // Fase 10.2 (P2): sin «Crear cuenta». La alta de usuarios es solo por
    // archivo plano (usuarios_iniciales.csv); RegistroView queda inalcanzable.

    private static String fallo(Exception e) {
        return e.getMessage() != null ? e.getMessage() : "no se pudo entrar";
    }
}
