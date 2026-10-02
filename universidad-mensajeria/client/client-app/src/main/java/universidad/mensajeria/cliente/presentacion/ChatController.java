package universidad.mensajeria.cliente.presentacion;

import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.prefs.Preferences;
import java.util.logging.Logger;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import universidad.mensajeria.cliente.fachada.FachadaCliente;
import universidad.mensajeria.cliente.presentacion.celdas.MensajeCell;
import universidad.mensajeria.cliente.presentacion.celdas.UsuarioCell;
import universidad.mensajeria.cliente.transversal.records.MensajeLocal;
import universidad.mensajeria.cliente.transversal.records.RecienteChat;
import universidad.mensajeria.cliente.transversal.records.UsuarioLocal;
import universidad.mensajeria.cliente.transversal.utilerias.FormatoRespuesta;
import universidad.mensajeria.cliente.transversal.utilerias.FormatoUsuario;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.common.tipos.TipoMensaje;

/** Presentación JavaFX: no accede directamente a red ni a persistencia. */
public final class ChatController {

    private static final int LIMITE_PAGINA = 50;
    private static final Set<String> EXT_IMAGEN = Set.of("png", "jpg", "jpeg", "gif");
    private static final String PREF_ULTIMO = "ultimoChat";
    private static final int RESPALDO_SEGUNDOS = 30;
    private static final long INTERVALO_MINIMO_MS = 200;
    private static final Logger LOG = Logger.getLogger(ChatController.class.getName());

    @FXML private Label etiquetaSesion;
    @FXML private Label etiquetaChat;
    @FXML private Label etiquetaPresencia;
    @FXML private Label etiquetaEstado;
    @FXML private Label etiquetaRespuesta;
    @FXML private Label etiquetaAdjunto;
    @FXML private javafx.scene.layout.HBox barraRespuesta;
    @FXML private javafx.scene.layout.HBox barraAdjunto;
    @FXML private ComboBox<String> comboFiltro;
    @FXML private TextField campoBuscar;
    @FXML private TextArea campoEntrada;
    @FXML private ListView<RecienteChat> listaUsuarios;
    @FXML private ListView<MensajeLocal> listaMensajes;
    @FXML private ImageView vistaPrevia;
    @FXML private Button botonMas;
    @FXML private Button botonEnviar;
    @FXML private Button botonDifundir;
    @FXML private Label bannerOffline;

    private FachadaCliente fachada;
    private Preferences prefs;
    private byte[] imagenLista;
    private String nombreImagenLista;
    private MensajeLocal respuestaSeleccionada;
    private int offsetActual;
    private final BooleanProperty enviando = new SimpleBooleanProperty();
    private final BooleanProperty adjuntoDisponible = new SimpleBooleanProperty();
    private final BooleanProperty hayDestino = new SimpleBooleanProperty();
    private final Map<String, UsuarioLocal> directorio = new HashMap<>();
    private final Map<String, Integer> paginaPorMensaje = new HashMap<>();
    private final Set<String> idsCargaImagen = new HashSet<>();
    private final Set<String> idsImagenFallida = new HashSet<>();
    private final Map<String, Object> locksMetadata = new ConcurrentHashMap<>();
    private final Consumer<Mensaje> alEntrar = this::alMensajeEntrante;
    private final Map<String, Integer> paginaRemota = new ConcurrentHashMap<>();
    private final Map<String, Integer> totalRemoto = new ConcurrentHashMap<>();
    private int versionAdjunto;
    private Timeline respaldo;
    private boolean refrescoEnCurso;
    private boolean cierreRemotoProcesado;
    private long ultimoRefrescoMs;
    private ObservableList<RecienteChat> todos = FXCollections.observableArrayList();

    @FXML
    private void initialize() {
        fachada = ClienteApp.fachada();
        prefs = Preferences.userNodeForPackage(ChatController.class);
        etiquetaSesion.setText("Sesión: " + nombreVisible(fachada.codigoActual()));
        listaUsuarios.setCellFactory(v -> new UsuarioCell(this::nombreVisible));
        listaMensajes.setCellFactory(v -> new MensajeCell(this::cargarImagenVisible,
                this::reintentarImagen, this::alResponder, this::nombreVisible,
                idsImagenFallida::contains, this::alDescargarArchivo));
        comboFiltro.setItems(FXCollections.observableArrayList("Todos", "Conectados", "No leídos"));
        comboFiltro.getSelectionModel().selectFirst();
        campoBuscar.textProperty().addListener((o, a, b) -> aplicarFiltro());
        comboFiltro.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> aplicarFiltro());
        campoEntrada.textProperty().addListener((o, a, b) -> actualizarEstadoEnviar());
        listaUsuarios.getSelectionModel().selectedItemProperty().addListener((obs, anterior, actual) -> {
            hayDestino.set(actual != null);
            if (anterior != null && actual != null && !anterior.otro().equals(actual.otro())) {
                cancelarRespuesta();
            }
            if (actual != null) {
                prefs.put(PREF_ULTIMO + "_" + fachada.codigoActual(), actual.otro());
                offsetActual = 0;
                paginaPorMensaje.clear();
                etiquetaChat.setText(nombreVisible(actual.otro()));
                etiquetaPresencia.setText(actual.conectado() ? "en línea" : "desconectado");
                cargarConversacionFondo();
                fachada.marcarLeidosFondo(actual.otro());
            } else {
                etiquetaChat.setText("Selecciona una conversación");
                etiquetaPresencia.setText("");
            }
            actualizarEstadoEnviar();
        });
        botonEnviar.disableProperty().bind(Bindings.createBooleanBinding(
                () -> !puedeEnviar(campoEntrada.getText(), imagenLista != null,
                        hayDestino.get(), enviando.get()),
                campoEntrada.textProperty(), enviando, adjuntoDisponible, hayDestino));
        botonDifundir.disableProperty().bind(Bindings.createBooleanBinding(
                () -> enviando.get() || campoEntrada.getText() == null
                        || campoEntrada.getText().isBlank(),
                campoEntrada.textProperty(), enviando));
        fachada.desuscribir(alEntrar);
        fachada.suscribir(alEntrar);
        configurarDragDrop();
        actualizarBannerOffline();
        alActualizar();
        respaldo = new Timeline(new KeyFrame(Duration.seconds(RESPALDO_SEGUNDOS), e -> actualizarFondo()));
        respaldo.setCycleCount(Timeline.INDEFINITE);
        respaldo.play();
    }

    public void detener() {
        if (respaldo != null) respaldo.stop();
        refrescoEnCurso = false;
        fachada.desuscribir(alEntrar);
    }

    private void actualizarEstadoEnviar() {
        if (botonEnviar != null && !botonEnviar.disableProperty().isBound()) {
            botonEnviar.setDisable(!puedeEnviar(campoEntrada.getText(), imagenLista != null,
                    seleccionado() != null, enviando.get()));
        }
    }

    public static boolean puedeEnviar(String texto, boolean imagen, boolean hayDestino, boolean enviando) {
        return hayDestino && !enviando && ((texto != null && !texto.isBlank()) || imagen);
    }

    private void aplicarFiltro() {
        String q = campoBuscar.getText() == null ? "" : campoBuscar.getText().trim().toLowerCase(Locale.ROOT);
        String filtro = comboFiltro.getSelectionModel().getSelectedItem();
        ObservableList<RecienteChat> vista = FXCollections.observableArrayList();
        for (RecienteChat r : todos) {
            UsuarioLocal usuario = directorio.get(r.otro());
            String nombre = usuario == null ? "" : (usuario.nombres() + " " + usuario.apellidos());
            String preview = r.ultimoContenido() == null ? "" : r.ultimoContenido();
            if (!q.isEmpty() && !r.otro().toLowerCase(Locale.ROOT).contains(q)
                    && !nombre.toLowerCase(Locale.ROOT).contains(q)
                    && !preview.toLowerCase(Locale.ROOT).contains(q)) continue;
            if ("Conectados".equals(filtro) && !r.conectado()) continue;
            if ("No leídos".equals(filtro) && r.noLeidos() <= 0) continue;
            vista.add(r);
        }
        RecienteChat sel = listaUsuarios.getSelectionModel().getSelectedItem();
        listaUsuarios.setItems(vista);
        if (sel != null) {
            for (RecienteChat r : vista) {
                if (r.otro().equals(sel.otro())) {
                    listaUsuarios.getSelectionModel().select(r);
                    return;
                }
            }
        }
    }

    private String seleccionado() {
        RecienteChat r = listaUsuarios.getSelectionModel().getSelectedItem();
        return r == null ? null : r.otro();
    }

    public String nombreVisible(String codigo) {
        return FormatoUsuario.visible(directorio.get(codigo), codigo);
    }

    private void actualizarEncabezado() {
        String codigo = seleccionado();
        if (codigo == null) return;
        etiquetaChat.setText(nombreVisible(codigo));
        etiquetaPresencia.setText(todos.stream().filter(r -> r.otro().equals(codigo))
                .findFirst().map(r -> r.conectado() ? "en línea" : "desconectado").orElse(""));
    }

    @FXML
    private void alEnviar() {
        String destino = seleccionado();
        String texto = campoEntrada.getText() == null ? "" : campoEntrada.getText().trim();
        byte[] imagen = imagenLista == null ? null : imagenLista.clone();
        String nombre = nombreImagenLista;
        if (!puedeEnviar(texto, imagen != null, destino != null, enviando.get())) return;
        if (respuestaSeleccionada != null) {
            String prefijo = FormatoRespuesta.prefijo(respuestaSeleccionada,
                    nombreVisible(respuestaSeleccionada.origen()));
            texto = prefijo + texto;
        }
        final String contenido = texto;
        enviando.set(true);
        campoEntrada.clear();
        limpiarImagen();
        cancelarRespuesta();
        Task<Boolean> tarea = new Task<>() {
            @Override protected Boolean call() throws Exception {
                boolean enRed = true;
                if (!contenido.isBlank()) enRed = fachada.enviarTexto(destino, contenido);
                if (imagen != null) {
                    String ext = extension(nombre);
                    if (EXT_IMAGEN.contains(ext)) {
                        enRed = fachada.enviarImagen(destino, imagen, nombre,
                                "image/" + ext) && enRed;
                    } else {
                        enRed = fachada.enviarArchivo(destino, imagen, nombre,
                                mimeDe(nombre)) && enRed;
                    }
                }
                return enRed;
            }
        };
        tarea.setOnSucceeded(e -> {
            enviando.set(false);
            estado(tarea.getValue() ? "Enviado" : "Sin red: guardado para reintentar");
            cargarConversacionFondo();
        });
        tarea.setOnFailed(e -> {
            enviando.set(false);
            estado("No se completó el envío: " + mensaje(tarea.getException()));
            cargarConversacionFondo();
        });
        fachada.fondo().execute(tarea);
    }

    @FXML
    private void alTeclaEntrada(KeyEvent evento) {
        if (evento.getCode() == KeyCode.ENTER && !evento.isShiftDown()
                && botonEnviar != null && !botonEnviar.isDisabled()) {
            evento.consume();
            alEnviar();
        }
    }

    private void alResponder(MensajeLocal mensaje) {
        respuestaSeleccionada = mensaje;
        String quote = FormatoRespuesta.prefijo(mensaje, nombreVisible(mensaje.origen())).trim();
        etiquetaRespuesta.setText(quote.length() > 150 ? quote.substring(0, 147) + "…" : quote);
        barraRespuesta.setVisible(true);
        barraRespuesta.setManaged(true);
    }

    @FXML private void alCancelarRespuesta() { cancelarRespuesta(); }

    private void cancelarRespuesta() {
        respuestaSeleccionada = null;
        if (barraRespuesta != null) {
            barraRespuesta.setVisible(false);
            barraRespuesta.setManaged(false);
        }
    }

    @FXML
    private void alElegirImagen() {
        FileChooser elegidor = new FileChooser();
        elegidor.setTitle("Elegir archivo (imagen, PDF, documento...)");
        elegidor.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Todos", "*.*"),
                new FileChooser.ExtensionFilter("Imágenes", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp"),
                new FileChooser.ExtensionFilter("Documentos", "*.pdf", "*.txt", "*.docx", "*.xlsx", "*.pptx", "*.zip"));
        cargarArchivo(elegidor.showOpenDialog(listaMensajes.getScene().getWindow()));
    }

    @FXML private void alQuitarImagen() { limpiarImagen(); actualizarEstadoEnviar(); }

    private void limpiarImagen() {
        versionAdjunto++;
        imagenLista = null;
        nombreImagenLista = null;
        adjuntoDisponible.set(false);
        vistaPrevia.setImage(null);
        barraAdjunto.setVisible(false);
        barraAdjunto.setManaged(false);
        actualizarEstadoEnviar();
    }

    private void cargarArchivo(File archivo) {
        if (archivo == null) return;
        int version = ++versionAdjunto;
        Task<byte[]> tarea = new Task<>() {
            @Override protected byte[] call() throws Exception {
                byte[] bytes = Files.readAllBytes(archivo.toPath());
                if (bytes.length > 50 * 1024 * 1024) throw new IllegalStateException("Máximo 50 MiB");
                return bytes;
            }
        };
        tarea.setOnSucceeded(e -> {
            if (version != versionAdjunto) return;
            byte[] bytesArchivo = tarea.getValue();
            imagenLista = bytesArchivo;
            nombreImagenLista = archivo.getName();
            boolean esImagen = EXT_IMAGEN.contains(extension(archivo.getName()));
            Task<Image> preview = new Task<>() {
                @Override protected Image call() {
                    if (!esImagen) {
                        return null;
                    }
                    return new Image(new java.io.ByteArrayInputStream(bytesArchivo), 120, 0, true, true);
                }
            };
            preview.setOnSucceeded(done -> {
                if (version != versionAdjunto) return;
                vistaPrevia.setImage(preview.getValue());
                etiquetaAdjunto.setText(nombreImagenLista
                        + (esImagen ? "" : " (archivo generico)"));
                adjuntoDisponible.set(true);
                barraAdjunto.setVisible(true);
                barraAdjunto.setManaged(true);
                actualizarEstadoEnviar();
            });
            fachada.fondo().execute(preview);
        });
        tarea.setOnFailed(e -> estado("No se pudo leer el archivo: " + mensaje(tarea.getException())));
        fachada.fondo().execute(tarea);
    }

    private void configurarDragDrop() {
        listaMensajes.setOnDragOver(e -> {
            Dragboard db = e.getDragboard();
            if (db.hasFiles() && !db.getFiles().isEmpty()) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        listaMensajes.setOnDragDropped(e -> {
            Dragboard db = e.getDragboard();
            boolean ok = false;
            if (db.hasFiles()) {
                for (File f : db.getFiles()) {
                    if (f.isFile()) {
                        cargarArchivo(f);
                        ok = true;
                        break;
                    }
                }
            }
            e.setDropCompleted(ok);
            e.consume();
        });
    }

    @FXML
    private void alDifundir() {
        String texto = campoEntrada.getText() == null ? "" : campoEntrada.getText().trim();
        if (texto.isBlank() || enviando.get()) return;
        enviando.set(true);
        campoEntrada.clear();
        Task<Void> tarea = new Task<>() {
            @Override protected Void call() throws Exception { fachada.difundir(texto); return null; }
        };
        tarea.setOnSucceeded(e -> { enviando.set(false); estado("Difundido a todos"); actualizarFondo(); });
        tarea.setOnFailed(e -> { enviando.set(false); estado("No se pudo difundir: " + mensaje(tarea.getException())); });
        fachada.fondo().execute(tarea);
    }

    @FXML
    private void alActualizar() {
        estado("Actualizando directorio y presencia…");
        Task<Void> tarea = new Task<>() {
            @Override protected Void call() {
                try { fachada.sincronizarDirectorio(); }
                catch (Exception e) { LOG.fine(() -> "directorio remoto omitido: " + mensaje(e)); }
                try { fachada.refrescarPresencia(); }
                catch (Exception e) { LOG.fine(() -> "presencia manual omitida: " + mensaje(e)); }
                return null;
            }
        };
        tarea.setOnSucceeded(e -> {
            actualizarFondo();
            if (seleccionado() == null) restaurarUltimo();
        });
        fachada.fondo().execute(tarea);
    }

    private record Carga(String sel, List<RecienteChat> recientes, List<UsuarioLocal> usuarios,
                         List<MensajeLocal> pagina, int total, int offset) { }

    private void actualizarFondo() {
        long ahora = System.currentTimeMillis();
        if (refrescoEnCurso || ahora - ultimoRefrescoMs < INTERVALO_MINIMO_MS) return;
        refrescoEnCurso = true;
        ultimoRefrescoMs = ahora;
        String sel = seleccionado();
        int off = offsetActual;
        long inicio = System.nanoTime();
        Task<Carga> tarea = new Task<>() {
            @Override protected Carga call() throws Exception {
                List<UsuarioLocal> usuarios = fachada.directorioLocal();
                List<RecienteChat> rec = fachada.recientes();
                if (sel == null) return new Carga(null, rec, usuarios, List.of(), 0, off);
                List<MensajeLocal> pag = fachada.conversacion(sel, LIMITE_PAGINA, off);
                return new Carga(sel, rec, usuarios, pag, fachada.contarConversacion(sel), off);
            }
        };
        tarea.setOnSucceeded(e -> {
            refrescoEnCurso = false;
            Carga c = tarea.getValue();
            directorio.clear();
            for (UsuarioLocal u : c.usuarios()) directorio.put(u.codigo(), u);
            etiquetaSesion.setText("Sesión: " + nombreVisible(fachada.codigoActual()));
            todos.setAll(c.recientes());
            aplicarFiltro();
            restaurarSeleccion(c.sel());
            actualizarEncabezado();
            pintarPagina(c.sel(), c.pagina(), c.total(), c.offset());
            listaMensajes.refresh();
            estado("Recientes: " + c.recientes().size());
            long ms = (System.nanoTime() - inicio) / 1_000_000;
            if (ms > 200 || fachada.suscriptores() > 1) {
                LOG.info(() -> "[perf] refresco local=" + ms + "ms subs=" + fachada.suscriptores());
            }
        });
        tarea.setOnFailed(e -> {
            refrescoEnCurso = false;
            LOG.fine(() -> "refresco local omitido: " + mensaje(tarea.getException()));
        });
        fachada.fondo().execute(tarea);
    }

    private void restaurarSeleccion(String sel) {
        if (sel == null) return;
        for (RecienteChat r : listaUsuarios.getItems()) {
            if (r.otro().equals(sel)) {
                listaUsuarios.getSelectionModel().select(r);
                return;
            }
        }
    }

    private void restaurarUltimo() {
        try {
            String ultimo = prefs.get(PREF_ULTIMO + "_" + fachada.codigoActual(), null);
            if (ultimo == null) return;
            for (RecienteChat r : listaUsuarios.getItems()) {
                if (r.otro().equals(ultimo)) {
                    listaUsuarios.getSelectionModel().select(r);
                    return;
                }
            }
        } catch (Exception ignorada) { /* preferencia opcional */ }
    }

    @FXML private void alCargarMas() { cargarMas(); }

    private void cargarMas() {
        String otro = seleccionado();
        if (otro == null) return;
        int mostrados = listaMensajes.getItems().size();
        Task<Boolean> tarea = new Task<>() {
            @Override protected Boolean call() throws Exception {
                int totalLocal = fachada.contarConversacion(otro);
                if (offsetActual + LIMITE_PAGINA < totalLocal) {
                    offsetActual += LIMITE_PAGINA;
                    return false;
                }
                int siguiente = paginaRemota.getOrDefault(otro, 0);
                Integer total = totalRemoto.get(otro);
                if (total != null && siguiente >= total) return null;
                FachadaCliente.PaginaRemota remota = fachada.traerHistorial(otro, siguiente);
                paginaRemota.put(otro, siguiente + 1);
                totalRemoto.put(otro, remota.totalPaginas());
                offsetActual = mostrados;
                return true;
            }
        };
        tarea.setOnSucceeded(e -> {
            Boolean trajo = tarea.getValue();
            if (trajo == null) estado("No hay más historial"); else cargarConversacionFondo();
        });
        tarea.setOnFailed(e -> estado("Error al cargar historial: " + mensaje(tarea.getException())));
        fachada.fondo().execute(tarea);
    }

    private void cargarImagenVisible(MensajeLocal item) {
        if (item == null || !idsCargaImagen.add(item.idMensaje())) return;
        String codigoActual = fachada.codigoActual();
        String chat = codigoActual != null && codigoActual.equals(item.origen())
                ? item.destino() : item.origen();
        int paginaMetadata = paginaPorMensaje.getOrDefault(item.idMensaje(), 0);
        Task<byte[]> tarea = new Task<>() {
            @Override protected byte[] call() throws Exception {
                byte[] bytes;
                try {
                    bytes = fachada.descargarImagen(item.idMensaje());
                } catch (IllegalStateException faltaId) {
                    if (faltaId.getMessage() == null || !faltaId.getMessage().contains("metadatos")) throw faltaId;
                    int pagina = paginaMetadata;
                    String clave = chat + ":" + pagina;
                    Object lock = locksMetadata.computeIfAbsent(clave, k -> new Object());
                    synchronized (lock) {
                        try { bytes = fachada.descargarImagen(item.idMensaje()); }
                        catch (IllegalStateException aunSinId) {
                            if (aunSinId.getMessage() == null || !aunSinId.getMessage().contains("metadatos")) throw aunSinId;
                            FachadaCliente.PaginaRemota remota = fachada.traerHistorial(chat, pagina);
                            paginaRemota.merge(chat, pagina + 1, Math::max);
                            totalRemoto.put(chat, remota.totalPaginas());
                            bytes = fachada.descargarImagen(item.idMensaje());
                        }
                    }
                }
                MensajeCell.precalentar(item.idMensaje(), Base64.getEncoder().encodeToString(bytes));
                return bytes;
            }
        };
        tarea.setOnSucceeded(e -> {
            idsCargaImagen.remove(item.idMensaje());
            idsImagenFallida.remove(item.idMensaje());
            listaMensajes.refresh();
        });
        tarea.setOnFailed(e -> {
            idsCargaImagen.remove(item.idMensaje());
            idsImagenFallida.add(item.idMensaje());
            LOG.fine(() -> "imagen no disponible " + item.idMensaje() + ": " + mensaje(tarea.getException()));
            listaMensajes.refresh();
        });
        fachada.fondo().execute(tarea);
    }

    private void reintentarImagen(MensajeLocal item) {
        idsImagenFallida.remove(item.idMensaje());
        cargarImagenVisible(item);
    }

    private void alDescargarArchivo(MensajeLocal item) {
        if (item == null) {
            return;
        }
        javafx.stage.FileChooser elegidor = new javafx.stage.FileChooser();
        elegidor.setTitle("Guardar archivo");
        elegidor.setInitialFileName(item.nombreArchivo() == null ? "archivo.bin" : item.nombreArchivo());
        File destino = elegidor.showSaveDialog(listaMensajes.getScene().getWindow());
        if (destino == null) {
            return;
        }
        estado("Descargando " + item.nombreArchivo() + "…");
        Task<Void> tarea = new Task<>() {
            @Override protected Void call() throws Exception {
                byte[] bytes;
                try {
                    bytes = fachada.descargarImagen(item.idMensaje());
                } catch (Exception e) {
                    // descargarImagen resuelve por archivoId; si falla, pedir descarga directa
                    universidad.mensajeria.common.tipos.Mensaje resp =
                            fachada.descargarArchivo(item.archivoId());
                    if (resp == null || resp.contenidoImagen() == null) {
                        throw new IllegalStateException("descarga no disponible");
                    }
                    bytes = Base64.getDecoder().decode(resp.contenidoImagen());
                }
                Files.write(destino.toPath(), bytes);
                return null;
            }
        };
        tarea.setOnSucceeded(e -> estado("Archivo guardado: " + destino.getName()));
        tarea.setOnFailed(e -> estado("No se pudo descargar: " + mensaje(tarea.getException())));
        fachada.fondo().execute(tarea);
    }

    private void pintarPagina(String otro, List<MensajeLocal> pagina, int total, int off) {
        if (otro == null) return;
        for (int i = 0; i < pagina.size(); i++) {
            MensajeLocal m = pagina.get(i);
            int rankDesdeMasNuevo = off + pagina.size() - 1 - i;
            paginaPorMensaje.put(m.idMensaje(), rankDesdeMasNuevo / LIMITE_PAGINA);
        }
        if (off == 0) {
            listaMensajes.getItems().setAll(pagina);
            if (!pagina.isEmpty()) listaMensajes.scrollTo(pagina.size() - 1);
        } else {
            Set<String> existentes = new HashSet<>();
            for (MensajeLocal m : listaMensajes.getItems()) existentes.add(m.idMensaje());
            ObservableList<MensajeLocal> combinada = FXCollections.observableArrayList();
            for (MensajeLocal m : pagina) if (!existentes.contains(m.idMensaje())) combinada.add(m);
            combinada.addAll(listaMensajes.getItems());
            listaMensajes.setItems(combinada);
        }
        boolean mas = total > listaMensajes.getItems().size() || (totalRemoto.containsKey(otro)
                && paginaRemota.getOrDefault(otro, 0) < totalRemoto.get(otro));
        botonMas.setVisible(mas);
        botonMas.setManaged(mas);
    }

    private void cargarConversacionFondo() {
        String otro = seleccionado();
        if (otro == null) return;
        int off = offsetActual;
        Task<Carga> tarea = new Task<>() {
            @Override protected Carga call() throws Exception {
                List<MensajeLocal> pag = fachada.conversacion(otro, LIMITE_PAGINA, off);
                return new Carga(otro, List.of(), List.of(), pag, fachada.contarConversacion(otro), off);
            }
        };
        tarea.setOnSucceeded(e -> pintarPagina(otro, tarea.getValue().pagina(),
                tarea.getValue().total(), off));
        tarea.setOnFailed(e -> estado("Error: " + mensaje(tarea.getException())));
        fachada.fondo().execute(tarea);
    }

    private void marcarLeidosAsync(String otro) { fachada.marcarLeidosFondo(otro); }

    private void alMensajeEntrante(Mensaje mensaje) {
        if (mensaje.tipo() == TipoMensaje.PRESENCIA) {
            Platform.runLater(this::actualizarFondo);
            return;
        }
        Platform.runLater(() -> {
            if (mensaje.tipo() == TipoMensaje.CLOSE_NOTICE) {
                if (cierreRemotoProcesado) return;
                cierreRemotoProcesado = true;
                String motivo = mensaje.mensajeError() == null || mensaje.mensajeError().isBlank()
                        ? "Sesión cerrada por el servidor."
                        : mensaje.mensajeError();
                estado("Sesión cerrada: " + motivo);
                detener();
                fachada.cerrarSesionRemota();
                ClienteApp.avisarProximoLogin("Sesión cerrada por inactividad (10 min), inicie de nuevo. "
                        + motivo + " (CLOSE_NOTICE RF-C08).");
                try { ClienteApp.mostrarLogin(); }
                catch (Exception e) { estado("No se pudo volver al inicio: " + mensaje(e)); }
                return;
            }
            String afectado = mensaje.remitente() != null ? mensaje.remitente() : mensaje.destinatario();
            String sel = seleccionado();
            actualizarFondo();
            if (sel != null && sel.equals(afectado) && !esAcuse(mensaje.tipo())) marcarLeidosAsync(sel);
        });
    }

    public static boolean esAcuse(TipoMensaje tipo) {
        return tipo == TipoMensaje.MENSAJE_LEIDO || tipo == TipoMensaje.MENSAJE_ENTREGADO;
    }

    @FXML
    private void alKick() {
        Task<Void> tarea = new Task<>() {
            @Override protected Void call() throws Exception { fachada.expulsarOtrasSesiones(); return null; }
        };
        tarea.setOnSucceeded(e -> estado("Se solicitó cerrar tus otras sesiones"));
        tarea.setOnFailed(e -> estado("Error: " + mensaje(tarea.getException())));
        fachada.fondo().execute(tarea);
    }

    @FXML private void alSalir() { salirAlInicio(false); }

    @FXML
    private void alSalirTodos() {
        salirAlInicio(true);
    }

    void salirAlInicio() {
        salirAlInicio(false);
    }

    private void salirAlInicio(boolean todasLasSesiones) {
        detener();
        Task<Void> tarea = new Task<>() {
            @Override protected Void call() throws Exception {
                if (todasLasSesiones) fachada.expulsarOtrasSesiones();
                try { fachada.logout(); } catch (Exception ignorada) { fachada.desconectar(); }
                return null;
            }
        };
        tarea.setOnSucceeded(e -> { try { ClienteApp.mostrarLogin(); } catch (Exception err) { estado(mensaje(err)); } });
        fachada.fondo().execute(tarea);
    }

    private void estado(String texto) {
        etiquetaEstado.setText(texto);
        actualizarBannerOffline();
    }

    private void actualizarBannerOffline() {
        if (bannerOffline == null || fachada == null) {
            return;
        }
        boolean sinRed = !fachada.conectado();
        bannerOffline.setVisible(sinRed);
        bannerOffline.setManaged(sinRed);
    }

    private static String mensaje(Throwable e) {
        return e == null ? "error desconocido" : e.getMessage() != null ? e.getMessage() : e.toString();
    }

    private static String extension(String nombre) {
        int punto = nombre == null ? -1 : nombre.lastIndexOf('.');
        return punto < 0 ? "" : nombre.substring(punto + 1).toLowerCase(Locale.ROOT);
    }

    private static String mimeDe(String nombre) {
        String ext = extension(nombre);
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "webp" -> "image/webp";
            case "pdf" -> "application/pdf";
            case "txt" -> "text/plain";
            case "zip" -> "application/zip";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            default -> "application/octet-stream";
        };
    }
}
