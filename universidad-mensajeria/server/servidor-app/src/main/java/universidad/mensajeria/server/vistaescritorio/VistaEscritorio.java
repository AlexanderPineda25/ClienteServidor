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
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import universidad.mensajeria.common.interno.InformeDTO;
import universidad.mensajeria.common.interno.InformeFiltroDTO;
import universidad.mensajeria.common.interno.MensajeResumenDTO;
import universidad.mensajeria.common.interno.UsuarioResumen;
import universidad.mensajeria.server.transversal.fachada.Fachada;
import universidad.mensajeria.utilerias.imagen.Imagenes;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Vista Escritorio (JavaFX, diagrama A2.1): habla SOLO con Fachada para datos
 * (Estado, usuarios, eventos, informes, mensajes detalle, bytes de archivos).
 * La previsualizacion con filtros es solo render local (Imagenes puras),
 * sin logica de negocio: no persiste ni reordena la tuberia del servidor.
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

    // Difusion administrativa (solo a todos, sin selector de destinatario)
    private TextField campoDifundir;
    private Label estadoDifundir;

    // Detalle de mensajes: contenido + imagen + filtros visuales
    private Tab tabMensajes;
    private TextField filtroCodigoMensaje;
    private ComboBox<String> comboTipoMensaje;
    private Label estadoMensajes;
    private TableView<MensajeResumenDTO> tablaMensajes;
    private Label detalleTitulo;
    private TextArea detalleContenido;
    private Label detalleMeta;
    private ImageView vistaMensaje;
    private ComboBox<String> comboFiltroVisual;
    private Label estadoFiltroVisual;
    private byte[] bytesImagenActual;
    private String nombreImagenActual = "";

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
        tabMensajes = pestanaMensajes();
        tabs = new TabPane(
                pestanaEstado(f),
                pestanaUsuarios(f),
                pestanaConectados(f),
                pestanaEventos(f),
                pestanaDifundir(),
                tabMensajes,
                tabInformes);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        BorderPane raiz = new BorderPane(tabs);
        HBox barra = new HBox(8,
                boton("Iniciar", () -> ejecutarSeguro(f::iniciarServidor)),
                boton("Detener", () -> ejecutarSeguro(f::detenerServidor)),
                boton("Refrescar", this::refrescar));
        barra.setStyle("-fx-padding: 8;");
        raiz.setBottom(barra);

        if (f != null) {
            f.suscribirEventos(linea -> Platform.runLater(() -> {
                if (eventos != null) {
                    eventos.appendText(linea + "\n");
                }
            }));
        }

        escenario.setScene(new Scene(raiz, 960, 620));
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

    /** Difusion administrativa: solo a TODOS, sin selector de destinatario. */
    private Tab pestanaDifundir() {
        campoDifundir = new TextField();
        campoDifundir.setPromptText("Mensaje para toda la comunidad (broadcast, sin destinatario unico)");
        estadoDifundir = new Label("La difusion llega a todos los conectados.");
        Button difundir = boton("Difundir a todos", () -> ejecutarSeguro(() -> {
            Fachada f = fachada;
            if (f == null) {
                return;
            }
            String contenido = campoDifundir.getText() == null ? "" : campoDifundir.getText().trim();
            if (contentoVacio(contenido)) {
                estadoDifundir.setText("Escribe un mensaje no vacio para difundir.");
                return;
            }
            try {
                f.difundirAdministrativo(contenido);
                estadoDifundir.setText("Difusion encolada para todos.");
                campoDifundir.clear();
            } catch (RuntimeException e) {
                estadoDifundir.setText("No se pudo difundir: " + e.getMessage());
            }
        }));
        HBox fila = new HBox(8, campoDifundir, difundir);
        fila.setStyle("-fx-padding: 8;");
        HBox.setHgrow(campoDifundir, Priority.ALWAYS);
        Label ayuda = new Label("El servidor no puede elegir un unico destinatario: siempre es broadcast.");
        ayuda.setWrapText(true);
        VBox caja = new VBox(8, fila, estadoDifundir, ayuda);
        caja.setStyle("-fx-padding: 12;");
        return new Tab("Difundir", caja);
    }

    private static boolean contentoVacio(String contenido) {
        return contenido == null || contenido.isBlank();
    }

    // ------------------------------------------------- Detalle de mensajes

    private Tab pestanaMensajes() {
        filtroCodigoMensaje = new TextField();
        filtroCodigoMensaje.setPromptText("codigo o SERVIDOR (vacio = todos)");
        filtroCodigoMensaje.setPrefWidth(220);
        comboTipoMensaje = new ComboBox<>();
        comboTipoMensaje.getItems().setAll("Todos", "TEXTO", "IMAGEN", "ARCHIVO", "BROADCAST");
        comboTipoMensaje.setValue("Todos");
        Button actualizar = boton("Actualizar", () -> ejecutarSeguro(this::actualizarMensajes));
        HBox filtros = new HBox(8, new Label("Codigo:"), filtroCodigoMensaje,
                new Label("Tipo:"), comboTipoMensaje, actualizar);
        filtros.setStyle("-fx-padding: 8;");

        estadoMensajes = new Label("Pulsa Actualizar para ver el detalle con contenido.");
        tablaMensajes = new TableView<>();
        tablaMensajes.getColumns().addAll(
                colMensaje("Id", 60, m -> String.valueOf(m.id())),
                colMensaje("Fecha", 130, m -> txt(m.fechaEnvio())),
                colMensaje("Remitente", 150, m -> m.remitente()),
                colMensaje("Destinatario", 150, m -> m.destinatario()),
                colMensaje("Tipo", 90, m -> txt(m.tipo())),
                colMensaje("Hash", 120, m -> corto(m.hashSha256())));
        tablaMensajes.setPrefHeight(220);
        tablaMensajes.getSelectionModel().selectedItemProperty().addListener(
                (obs, ant, actual) -> mostrarDetalle(actual));

        detalleTitulo = new Label("Detalle: selecciona un mensaje");
        detalleTitulo.setStyle("-fx-font-weight: bold;");
        detalleContenido = new TextArea();
        detalleContenido.setEditable(false);
        detalleContenido.setWrapText(true);
        detalleContenido.setPrefRowCount(4);
        detalleContenido.setPromptText("El contenido del texto aparece aqui (no solo hash/metricas).");
        detalleMeta = new Label(" ");
        detalleMeta.setWrapText(true);

        vistaMensaje = new ImageView();
        vistaMensaje.setFitWidth(340);
        vistaMensaje.setFitHeight(240);
        vistaMensaje.setPreserveRatio(true);
        vistaMensaje.setSmooth(true);

        comboFiltroVisual = new ComboBox<>();
        comboFiltroVisual.getItems().setAll(
                "Original", "Grises", "Sepia", "Giro 180", "Brillo x1.25", "Reducida 50%");
        comboFiltroVisual.setValue("Original");
        estadoFiltroVisual = new Label("Filtros de previsualizacion (no reescriben uploads).");
        estadoFiltroVisual.setWrapText(true);
        Button aplicarFiltro = boton("Previsualizar filtro", () -> ejecutarSeguro(this::aplicarFiltroVisual));
        HBox filaFiltro = new HBox(8, new Label("Filtro:"), comboFiltroVisual, aplicarFiltro);
        VBox cajaFiltros = new VBox(6, vistaMensaje, filaFiltro, estadoFiltroVisual);

        HBox detalle = new HBox(12, new VBox(6, detalleTitulo, detalleContenido, detalleMeta), cajaFiltros);
        HBox.setHgrow(detalle.getChildren().get(0), Priority.ALWAYS);
        VBox caja = new VBox(8, filtros, estadoMensajes, tablaMensajes, detalle);
        VBox.setVgrow(tablaMensajes, Priority.ALWAYS);
        return new Tab("Mensajes", caja);
    }

    private static TableColumn<MensajeResumenDTO, String> colMensaje(String titulo, int ancho,
            java.util.function.Function<MensajeResumenDTO, String> valor) {
        TableColumn<MensajeResumenDTO, String> col = new TableColumn<>(titulo);
        col.setCellValueFactory(d -> new ReadOnlyStringWrapper(valor.apply(d.getValue())));
        col.setPrefWidth(ancho);
        return col;
    }

    private void actualizarMensajes() {
        Fachada f = fachada;
        if (f == null) {
            return;
        }
        String codigo = filtroCodigoMensaje.getText() == null ? "" : filtroCodigoMensaje.getText().trim();
        String tipo = comboTipoMensaje.getValue();
        InformeFiltroDTO filtro = new InformeFiltroDTO(null, null, codigo);
        List<MensajeResumenDTO> todos;
        try {
            todos = f.mensajesDetalle(filtro);
        } catch (RuntimeException e) {
            estadoMensajes.setText("No se pudo cargar: " + e.getMessage());
            return;
        }
        List<MensajeResumenDTO> filtrados = todos.stream()
                .filter(m -> "Todos".equals(tipo) || (m.tipo() != null && m.tipo().equalsIgnoreCase(tipo)))
                .toList();
        tablaMensajes.getItems().setAll(filtrados);
        estadoMensajes.setText(filtrados.size() + " mensaje(s)"
                + (codigo.isEmpty() ? "" : " con " + codigo)
                + (todos.size() != filtrados.size() ? " (de " + todos.size() + " totales)" : ""));
        bytesImagenActual = null;
        vistaMensaje.setImage(null);
    }

    private void mostrarDetalle(MensajeResumenDTO m) {
        bytesImagenActual = null;
        vistaMensaje.setImage(null);
        if (m == null) {
            detalleTitulo.setText("Detalle: selecciona un mensaje");
            detalleContenido.clear();
            detalleMeta.setText(" ");
            return;
        }
        detalleTitulo.setText("Detalle #" + m.id() + " " + txt(m.tipo())
                + " " + txt(m.remitente()) + " -> " + txt(m.destinatario()));
        String tipo = m.tipo() == null ? "" : m.tipo().toUpperCase();
        StringBuilder meta = new StringBuilder();
        meta.append("Hash: ").append(txt(m.hashSha256()));
        if (m.numCaracteres() != null || m.numPalabras() != null) {
            meta.append(" · ").append(m.numCaracteres()).append(" car. · ")
                    .append(m.numPalabras()).append(" pal.");
        }
        if (m.nombreArchivo() != null) {
            meta.append(" · Archivo: ").append(m.nombreArchivo());
        }
        if (m.tamanoArchivo() != null) {
            meta.append(" (").append(m.tamanoArchivo()).append(" bytes)");
        }
        if (m.etapasFiltrado() != null && !m.etapasFiltrado().isBlank()) {
            meta.append("\nFiltros aplicados: ").append(resumirEtapas(m.etapasFiltrado()));
        }
        detalleMeta.setText(meta.toString());

        if ("TEXTO".equals(tipo) || "BROADCAST".equals(tipo)) {
            detalleContenido.setText(m.contenido() == null ? "(sin contenido)" : m.contenido());
            estadoFiltroVisual.setText("El texto no admite filtros visuales.");
            return;
        }
        // IMAGEN / ARCHIVO: contenido es null en BD; se muestra nombre + preview si hay bytes.
        StringBuilder cuerpo = new StringBuilder();
        if (m.nombreArchivo() != null) {
            cuerpo.append(m.nombreArchivo());
        }
        if (m.mime() != null) {
            cuerpo.append(" [").append(m.mime()).append("]");
        }
        cuerpo.append("\n(hash ").append(txt(m.hashSha256())).append(")");
        detalleContenido.setText(cuerpo.toString());
        if (m.archivoId() == null) {
            estadoFiltroVisual.setText("Sin archivoId: trae el historial remoto o revisa uploads.");
            return;
        }
        try {
            Fachada f = fachada;
            if (f == null) {
                return;
            }
            byte[] bytes = f.bytesArchivoParaVista(m.archivoId());
            nombreImagenActual = m.nombreArchivo() == null ? "archivo" : m.nombreArchivo();
            if (esImagen(bytes, m.mime(), nombreImagenActual)) {
                bytesImagenActual = bytes.clone();
                vistaMensaje.setImage(new Image(new ByteArrayInputStream(bytesImagenActual)));
                estadoFiltroVisual.setText("Imagen cargada: elige un filtro y pulsa Previsualizar.");
            } else {
                estadoFiltroVisual.setText("Archivo no imagen (" + bytes.length
                        + " bytes): solo metadatos, sin preview.");
            }
        } catch (RuntimeException e) {
            estadoFiltroVisual.setText("No se pudo cargar el archivo: " + e.getMessage());
        }
    }

    private void aplicarFiltroVisual() {
        if (bytesImagenActual == null || bytesImagenActual.length == 0) {
            estadoFiltroVisual.setText("Selecciona primero un mensaje de tipo IMAGEN.");
            return;
        }
        String opcion = comboFiltroVisual.getValue();
        try {
            java.awt.image.BufferedImage base = javax.imageio.ImageIO.read(
                    new ByteArrayInputStream(bytesImagenActual));
            if (base == null) {
                estadoFiltroVisual.setText("No se pudo decodificar la imagen.");
                return;
            }
            java.awt.image.BufferedImage out = switch (opcion) {
                case "Grises" -> Imagenes.aGrises(base);
                case "Sepia" -> Imagenes.aSepia(base);
                case "Giro 180" -> Imagenes.girar(base, 180);
                case "Brillo x1.25" -> Imagenes.ajustarBrillo(base, 1.25);
                case "Reducida 50%" -> Imagenes.reducir(base, 0.5);
                default -> Imagenes.nuevaCopia(base);
            };
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(out, "png", buf);
            vistaMensaje.setImage(new Image(new ByteArrayInputStream(buf.toByteArray())));
            estadoFiltroVisual.setText("Filtro aplicado en vista: " + opcion + " (no persiste).");
        } catch (Exception e) {
            estadoFiltroVisual.setText("No se pudo aplicar el filtro: " + e.getMessage());
        }
    }

    private static boolean esImagen(byte[] bytes, String mime, String nombre) {
        if (mime != null && mime.toLowerCase().startsWith("image/")) {
            return true;
        }
        String n = nombre == null ? "" : nombre.toLowerCase();
        if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".gif") || n.endsWith(".bmp") || n.endsWith(".webp")) {
            return true;
        }
        return bytes != null && bytes.length >= 4
                && ((bytes[0] & 0xFF) == 0x89 || (bytes[0] & 0xFF) == 0xFF
                        || (bytes[0] & 0xFF) == 0x47 || (bytes[0] & 0xFF) == 0x42);
    }

    private static String resumirEtapas(String json) {
        if (json == null || json.isBlank()) {
            return "-";
        }
        String recorte = json.length() > 400 ? json.substring(0, 400) + "..." : json;
        return recorte.replaceAll("\\s+", " ");
    }

    private static String txt(String valor) {
        return valor == null || valor.isBlank() ? "-" : valor;
    }

    private static String corto(String hash) {
        if (hash == null || hash.isBlank()) {
            return "-";
        }
        return hash.length() <= 12 ? hash : hash.substring(0, 12) + "...";
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
        textoCodigo.setPromptText("codigo usuario (o SERVIDOR)");
        textoCodigo.setPrefWidth(170);
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
        if (estado == null || tabla == null || conectados == null) {
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
            // Las pestañas de datos tambien se redibujan solas si estan a la vista.
            if (tabs != null) {
                var sel = tabs.getSelectionModel().getSelectedItem();
                if (sel == tabInformes) {
                    actualizarInforme();
                } else if (sel == tabMensajes) {
                    actualizarMensajes();
                }
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
