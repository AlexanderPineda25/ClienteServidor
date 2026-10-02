package universidad.mensajeria.server.vistaescritorio;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import universidad.mensajeria.common.interno.InformeDTO;
import universidad.mensajeria.common.interno.InformeFiltroDTO;
import universidad.mensajeria.common.interno.UsuarioResumen;
import universidad.mensajeria.server.transversal.fachada.Fachada;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Vista Escritorio (JavaFX, diagrama A2.1): habla SOLO con Fachada.
 * Estado, usuarios registrados, conectados, eventos en vivo (req. #12)
 * e informes con filtros y export CSV (FASE 9, §8).
 * Sin anotaciones ni logica: la construye InicioServidor via Fabrica.
 */
public class VistaEscritorio extends Application {

    private static volatile Fachada fachada;

    private TextArea eventos;
    private Label estado;
    private TableView<UsuarioResumen> tabla;
    private ListView<String> conectados;

    // FASE 9: pestaña Informes
    private Stage escenario;
    private TabPane tabs;
    private Tab tabInformes;
    private ComboBox<String> comboTipo;
    private DatePicker desde;
    private DatePicker hasta;
    private TextField textoCodigo;
    private Label estadoInforme;
    private TableView<List<String>> tablaInformes;
    private InformeDTO ultimoInforme;

    /** Lo invoca InicioServidor antes de lanzar (DI manual). */
    public static void lanzar(Fachada fachadaLista) {
        fachada = fachadaLista;
        try {
            Application.launch(VistaEscritorio.class);
        } catch (IllegalStateException yaLanzado) {
            // toolkit ya arriba (tests): nada que hacer
        }
    }

    static void fachadaParaTests(Fachada fachadaLista) {
        fachada = fachadaLista;
    }

    @Override
    public void start(Stage escenario) {
        Fachada f = fachada;
        this.escenario = escenario;
        escenario.setTitle("Mensajeria Academica - Servidor");

        tabInformes = pestanaInformes(f);
        tabs = new TabPane(
                pestanaEstado(f),
                pestanaUsuarios(f),
                pestanaConectados(f),
                pestanaEventos(f),
                tabInformes);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        BorderPane raiz = new BorderPane(tabs);
        HBox barra = new HBox(8,
                boton("Iniciar", () -> ejecutarSeguro(f::iniciarServidor)),
                boton("Detener", () -> ejecutarSeguro(f::detenerServidor)),
                boton("Refrescar", this::refrescar));
        barra.setStyle("-fx-padding: 8;");
        raiz.setBottom(barra);

        f.suscribirEventos(linea -> Platform.runLater(() -> {
            eventos.appendText(linea + "\n");
        }));

        escenario.setScene(new Scene(raiz, 720, 480));
        escenario.show();
        refrescar();

        // Fase 10.4 (P1): auto-refresh cada 5 s de Estado/Usuarios/Conectados
        // (Eventos ya llega en vivo via suscribirEventos).
        Timeline autoRefresh = new Timeline(new KeyFrame(Duration.seconds(5),
                evento -> refrescarSeguro()));
        autoRefresh.setCycleCount(Timeline.INDEFINITE);
        autoRefresh.play();
    }

    private Tab pestanaEstado(Fachada f) {
        estado = new Label();
        VBox caja = new VBox(8, estado);
        caja.setStyle("-fx-padding: 12;");
        Tab tab = new Tab("Estado", caja);
        return tab;
    }

    private Tab pestanaUsuarios(Fachada f) {
        tabla = new TableView<>();
        tabla.getColumns().addAll(
                columna("Codigo", 110, u -> u.codigo()),
                columna("Nombres", 130, u -> u.nombres()),
                columna("Apellidos", 130, u -> u.apellidos()),
                columna("Programa", 180, u -> u.programa()),
                columna("Conectado", 90, u -> u.conectado() ? "Si" : "No"));
        VBox caja = new VBox(tabla);
        return new Tab("Usuarios", caja);
    }

    private static TableColumn<UsuarioResumen, String> columna(String titulo, int ancho,
                                                               java.util.function.Function<UsuarioResumen, String> valor) {
        TableColumn<UsuarioResumen, String> columna = new TableColumn<>(titulo);
        columna.setCellValueFactory(datos -> new ReadOnlyStringWrapper(valor.apply(datos.getValue())));
        columna.setPrefWidth(ancho);
        return columna;
    }

    private Tab pestanaConectados(Fachada f) {
        conectados = new ListView<>();
        TextField objetivo = new TextField();
        objetivo.setPromptText("codigo, id de sesion o *");
        Label resultado = new Label(" ");
        Button cerrar = boton("Cerrar seleccion", () -> {
            String blanco = objetivo.getText() == null ? "" : objetivo.getText().trim();
            if (blanco.isEmpty()) {
                resultado.setText("Indica un codigo, un id de sesion o *.");
                return;
            }
            try {
                int cerradas = f.cerrarConexionAdmin(blanco, "cierre administrativo");
                resultado.setText("Cerradas " + cerradas + " sesiones de " + blanco + ".");
                refrescarSeguro();
            } catch (RuntimeException e) {
                resultado.setText("No se pudo cerrar: " + e.getMessage());
            }
        });
        HBox barra = new HBox(8, new Label("Cerrar:"), objetivo, cerrar);
        barra.setStyle("-fx-padding: 8;");
        VBox caja = new VBox(8, conectados, barra, resultado);
        return new Tab("Conectados", caja);
    }

    private Tab pestanaEventos(Fachada f) {
        eventos = new TextArea();
        eventos.setEditable(false);
        VBox caja = new VBox(eventos);
        return new Tab("Eventos", caja);
    }

    // ------------------------------------------------- FASE 9: pestaña Informes

    private Tab pestanaInformes(Fachada f) {
        comboTipo = new ComboBox<>();
        comboTipo.getItems().setAll("usuarios", "conexiones", "mensajes", "auditoria");
        comboTipo.setValue("usuarios");
        desde = new DatePicker();
        desde.setPromptText("desde (aaaa-mm-dd)");
        hasta = new DatePicker();
        hasta.setPromptText("hasta (aaaa-mm-dd)");
        textoCodigo = new TextField();
        textoCodigo.setPromptText("codigo usuario");
        textoCodigo.setPrefWidth(130);
        estadoInforme = new Label(" ");

        Button actualizar = boton("Actualizar", () -> ejecutarSeguro(this::actualizarInforme));
        Button exportar = boton("Exportar CSV", () -> ejecutarSeguro(this::exportarCsv));
        HBox filtros = new HBox(8, new Label("Tipo:"), comboTipo,
                new Label("Desde:"), desde, new Label("Hasta:"), hasta,
                new Label("Codigo:"), textoCodigo, actualizar, exportar);
        filtros.setStyle("-fx-padding: 8;");

        tablaInformes = new TableView<>();
        VBox caja = new VBox(8, filtros, estadoInforme, tablaInformes);
        VBox.setVgrow(tablaInformes, javafx.scene.layout.Priority.ALWAYS);
        return new Tab("Informes", caja);
    }

    /** Genera el informe con los filtros de la pestaña (manual, NO auto-refresh). */
    private void actualizarInforme() {
        Fachada f = fachada;
        if (f == null) {
            return;
        }
        InformeFiltroDTO filtro = new InformeFiltroDTO(
                desde.getValue() == null ? null : desde.getValue().toString(),
                hasta.getValue() == null ? null : hasta.getValue().toString(),
                textoCodigo.getText() == null ? "" : textoCodigo.getText().trim());
        InformeDTO informe = switch (comboTipo.getValue()) {
            case "conexiones" -> f.informeConexiones(filtro);
            case "mensajes" -> f.informeMensajes(filtro);
            case "auditoria" -> f.informeAuditoria(filtro);
            default -> f.informeUsuarios(filtro);
        };
        ultimoInforme = informe;
        pintar(informe);
        estadoInforme.setText(informe.titulo() + " · " + informe.filas().size() + " fila(s)");
    }

    private void pintar(InformeDTO informe) {
        tablaInformes.getColumns().clear();
        List<String> columnas = informe.columnas();
        for (int i = 0; i < columnas.size(); i++) {
            int indice = i;
            TableColumn<List<String>, String> columna = new TableColumn<>(columnas.get(i));
            columna.setCellValueFactory(datos -> {
                List<String> fila = datos.getValue();
                String valor = indice < fila.size() && fila.get(indice) != null
                        ? fila.get(indice) : "";
                return new ReadOnlyStringWrapper(valor);
            });
            columna.setPrefWidth(indice == 0 ? 150 : 170);
            tablaInformes.getColumns().add(columna);
        }
        tablaInformes.getItems().setAll(informe.filas());
    }

    private void exportarCsv() {
        if (ultimoInforme == null) {
            actualizarInforme();
        }
        if (ultimoInforme == null) {
            return;
        }
        FileChooser elegidor = new FileChooser();
        elegidor.setTitle("Exportar informe");
        elegidor.setInitialFileName(ultimoInforme.nombreArchivoCsv());
        elegidor.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        File archivo = elegidor.showSaveDialog(escenario);
        if (archivo == null) {
            return;
        }
        List<String> lineas = new ArrayList<>();
        lineas.add(unirCsv(ultimoInforme.columnas()));
        for (List<String> fila : ultimoInforme.filas()) {
            lineas.add(unirCsv(fila));
        }
        try {
            Files.writeString(archivo.toPath(), String.join("\n", lineas) + "\n",
                    StandardCharsets.UTF_8);
            estadoInforme.setText("Exportado: " + archivo.getName());
        } catch (Exception e) {
            estadoInforme.setText("No se pudo exportar: " + e.getMessage());
        }
    }

    private static String unirCsv(List<String> campos) {
        StringBuilder linea = new StringBuilder();
        for (int i = 0; i < campos.size(); i++) {
            if (i > 0) {
                linea.append(',');
            }
            String valor = campos.get(i) == null ? "" : campos.get(i);
            if (valor.contains(",") || valor.contains("\"") || valor.contains("\n")) {
                linea.append('"').append(valor.replace("\"", "\"\"")).append('"');
            } else {
                linea.append(valor);
            }
        }
        return linea.toString();
    }

    private void refrescar() {
        Fachada f = fachada;
        if (f == null) {
            return;
        }
        var e = f.estadoServidor();
        estado.setText((e.activo() ? "ACTIVO" : "DETENIDO")
                + "  ·  puerto " + e.puerto()
                + "  ·  usuarios " + e.usuariosConectados()
                + "  ·  sesiones " + e.sesionesActivas() + "/" + e.maxConexiones()
                + "  ·  pool " + e.trabajadoresOcupados() + "/"
                + e.trabajadoresTotal() + " (libres " + e.trabajadoresDisponibles() + ")"
                + "  ·  procesados " + e.mensajesProcesados()
                + "  ·  cola " + e.mensajesEnCola());
        tabla.getItems().setAll(f.usuariosRegistrados());
        conectados.getItems().setAll(f.usuariosConectados());
    }

    private void refrescarSeguro() {
        try {
            refrescar();
            // La pestaña Informes tambien se redibuja sola si esta a la vista.
            if (tabs != null && tabs.getSelectionModel().getSelectedItem() == tabInformes) {
                actualizarInforme();
            }
        } catch (RuntimeException e) {
            // la vista no tumba: el error ya queda en logs
        }
    }

    private Button boton(String texto, Runnable accion) {
        Button boton = new Button(texto);
        boton.setOnAction(evento -> accion.run());
        return boton;
    }

    private static void ejecutarSeguro(Runnable accion) {
        try {
            accion.run();
        } catch (RuntimeException e) {
            // la vista no tumba: el error ya quedo en logs
        }
    }
}
