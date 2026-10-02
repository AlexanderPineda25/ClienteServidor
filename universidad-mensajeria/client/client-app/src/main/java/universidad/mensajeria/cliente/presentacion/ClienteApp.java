package universidad.mensajeria.cliente.presentacion;

import javafx.application.Application;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import universidad.mensajeria.cliente.fachada.FachadaCliente;
import atlantafx.base.theme.PrimerLight;

/**
 * Aplicación JavaFX del cliente principal (§7.1 presentacion).
 * Regla §11: solo llama a FachadaCliente; ningún socket ni H2 aquí.
 */
public final class ClienteApp extends Application {

    private static FachadaCliente fachada;
    private static Stage escenario;
    private static volatile String avisoPendiente;

    /** Lo invoca ClienteMain antes de launch (DI manual). */
    public static void iniciar(FachadaCliente fachadaCliente) {
        fachada = fachadaCliente;
    }

    public static FachadaCliente fachada() {
        if (fachada == null) {
            throw new IllegalStateException("ClienteMain debe inyectar la fachada antes de launch");
        }
        return fachada;
    }

    @Override
    public void start(Stage escenarioPrincipal) throws Exception {
        Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());
        escenario = escenarioPrincipal;
        escenario.setTitle("Mensajería Académica");
        mostrarLogin();
        escenario.show();
    }

    @Override
    public void stop() {
        try {
            fachada.desconectar();
        } catch (Exception ignorada) {
            // cierre de app
        }
        try {
            fachada.cerrar();
        } catch (Exception ignorada) {
            // executor ya apagado
        }
    }

    public static void mostrarLogin() throws Exception {
        escena("/vistas/login.fxml", "Mensajería Académica — Entrar", 440, 620);
        escenario.setOnCloseRequest(evento -> {
            if (!confirmar("¿Seguro que deseas salir de la aplicación?")) {
                evento.consume();
            }
        });
    }

    /** Aviso ámbar del mockup (ej. CLOSE_NOTICE por inactividad RF-C08). */
    public static void avisarProximoLogin(String motivo) {
        avisoPendiente = motivo;
    }

    static String consumirAviso() {
        String aviso = avisoPendiente;
        avisoPendiente = null;
        return aviso;
    }

    public static void mostrarChat() throws Exception {
        FXMLLoader cargador = new FXMLLoader(ClienteApp.class.getResource("/vistas/chat.fxml"));
        Parent raiz = cargador.load();
        escenario.setTitle("Mensajería Académica — Chat");
        escenario.setScene(new Scene(raiz, 1100, 700));
        // FASE 13.7: la X cierra la SESIÓN (LOGOUT), no solo el socket: sin esto
        // la sesión quedaba huérfana en el servidor hasta el timeout.
        ChatController chat = cargador.getController();
        escenario.setOnCloseRequest(evento -> {
            evento.consume();
            if (confirmar("¿Deseas cerrar la sesión y volver al inicio?")) {
                chat.salirAlInicio();
            }
        });
    }

    private static boolean confirmar(String pregunta) {
        Alert alerta = new Alert(Alert.AlertType.CONFIRMATION, pregunta,
                ButtonType.YES, ButtonType.NO);
        alerta.initOwner(escenario);
        alerta.setTitle("Confirmar salida");
        alerta.setHeaderText(null);
        ((Button) alerta.getDialogPane().lookupButton(ButtonType.NO)).setDefaultButton(true);
        return alerta.showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
    }

    private static void escena(String fxml, String titulo, int ancho, int alto) throws Exception {
        Parent raiz = FXMLLoader.load(ClienteApp.class.getResource(fxml));
        escenario.setTitle(titulo);
        escenario.setScene(new Scene(raiz, ancho, alto));
    }
}
