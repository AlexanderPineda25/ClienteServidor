package universidad.mensajeria.cliente.presentacion.celdas;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import universidad.mensajeria.cliente.transversal.records.MensajeLocal;

/** Celda virtualizada del historial: pinta metadatos y solo pide la imagen visible. */
public final class MensajeCell extends ListCell<MensajeLocal> {

    private static final Map<String, Image> MINIATURAS =
            Collections.synchronizedMap(new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
                    return size() > 100;
                }
            });

    private final Consumer<MensajeLocal> alVerImagen;
    private final Consumer<MensajeLocal> alReintentarImagen;
    private final Consumer<MensajeLocal> alResponder;
    private final Function<String, String> nombreVisible;
    private final Predicate<String> imagenFallida;
    private final Consumer<MensajeLocal> alDescargar;

    public MensajeCell(Consumer<MensajeLocal> alVerImagen,
                       Consumer<MensajeLocal> alReintentarImagen,
                       Consumer<MensajeLocal> alResponder,
                       Function<String, String> nombreVisible,
                       Predicate<String> imagenFallida) {
        this(alVerImagen, alReintentarImagen, alResponder, nombreVisible, imagenFallida, m -> { });
    }

    public MensajeCell(Consumer<MensajeLocal> alVerImagen,
                       Consumer<MensajeLocal> alReintentarImagen,
                       Consumer<MensajeLocal> alResponder,
                       Function<String, String> nombreVisible,
                       Predicate<String> imagenFallida,
                       Consumer<MensajeLocal> alDescargar) {
        this.alVerImagen = alVerImagen;
        this.alReintentarImagen = alReintentarImagen;
        this.alResponder = alResponder;
        this.nombreVisible = nombreVisible;
        this.imagenFallida = imagenFallida;
        this.alDescargar = alDescargar == null ? m -> { } : alDescargar;
        getStyleClass().add("chat-message-cell");
    }

    @Override
    protected void updateItem(MensajeLocal item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
            setGraphic(null);
            setText(null);
            return;
        }

        boolean mio = item.enviado();
        VBox burbuja = new VBox(5);
        burbuja.getStyleClass().addAll("message-bubble", mio ? "message-out" : "message-in");
        burbuja.setPadding(new Insets(8, 10, 6, 10));
        burbuja.setMaxWidth(460);

        if (!mio) {
            Label remitente = new Label(nombreVisible.apply(item.origen()));
            remitente.getStyleClass().add("message-sender");
            remitente.setTooltip(new Tooltip(remitente.getText()));
            burbuja.getChildren().add(remitente);
        }

        if ("MENSAJE_IMAGEN".equals(item.tipo())) {
            String detalle = item.nombreArchivo() == null ? "Imagen" : item.nombreArchivo();
            Label titulo = new Label(detalle);
            titulo.getStyleClass().add("image-caption");
            burbuja.getChildren().add(titulo);
            Image miniatura = MINIATURAS.get(item.idMensaje());
            if (miniatura != null && !miniatura.isError()) {
                ImageView vista = new ImageView(miniatura);
                vista.setFitWidth(300);
                vista.setFitHeight(240);
                vista.setPreserveRatio(true);
                vista.setSmooth(true);
                vista.setOnMouseClicked(e -> abrirGrande(item, miniatura));
                burbuja.getChildren().add(vista);
            } else {
                boolean fallo = imagenFallida.test(item.idMensaje());
                Label espera = new Label(fallo
                        ? "No se pudo cargar · reintentar" : "Cargando imagen…");
                espera.getStyleClass().add("image-placeholder");
                espera.setOnMouseClicked(e -> alReintentarImagen.accept(item));
                espera.setTooltip(new Tooltip("Cargar o reintentar imagen"));
                burbuja.getChildren().add(espera);
                if (!fallo) alVerImagen.accept(item);
            }
        } else if ("MENSAJE_ARCHIVO".equals(item.tipo())) {
            String nombre = item.nombreArchivo() == null ? "Archivo" : item.nombreArchivo();
            String tam = item.tamanoArchivo() != null ? " (" + item.tamanoArchivo() + " bytes)" : "";
            Label titulo = new Label("📎 " + nombre + tam);
            titulo.getStyleClass().add("image-caption");
            titulo.setWrapText(true);
            burbuja.getChildren().add(titulo);
            Button descargar = new Button("⬇ Descargar");
            descargar.getStyleClass().add("reply-button");
            descargar.setTooltip(new Tooltip("Descargar archivo"));
            descargar.setOnAction(e -> alDescargar.accept(item));
            burbuja.getChildren().add(descargar);
        } else {
            Label cuerpo = new Label(item.contenido() == null ? "" : item.contenido());
            cuerpo.setWrapText(true);
            cuerpo.setMaxWidth(430);
            cuerpo.getStyleClass().add("message-text");
            burbuja.getChildren().add(cuerpo);
        }

        String hora = item.fechaEnvio() != null && item.fechaEnvio().length() >= 16
                ? item.fechaEnvio().substring(11, 16) : "";
        HBox pie = new HBox(7);
        pie.setAlignment(Pos.CENTER_RIGHT);
        pie.getStyleClass().add("message-footer");
        Button responder = new Button("↩");
        responder.getStyleClass().add("reply-button");
        responder.setTooltip(new Tooltip("Responder a este mensaje"));
        responder.setAccessibleText("Responder a este mensaje");
        responder.setOnAction(e -> alResponder.accept(item));
        Label lHora = new Label(hora);
        lHora.getStyleClass().add("message-time");
        pie.getChildren().addAll(responder, lHora);
        if (mio) {
            Label tick = new Label(tickDe(item.estado()));
            tick.getStyleClass().addAll("message-tick",
                    "LEIDO".equals(item.estado()) ? "message-read" : "message-unread");
            String detalle = switch (item.estado() == null ? "" : item.estado()) {
                case "LEIDO" -> "Visto por " + nombreVisible.apply(item.destino());
                case "ENTREGADO" -> "Entregado";
                case "PENDIENTE" -> "Pendiente de envío";
                case "ERROR" -> "No se pudo enviar";
                default -> "Enviado";
            };
            tick.setTooltip(new Tooltip(detalle));
            pie.getChildren().add(tick);
        }
        burbuja.getChildren().add(pie);

        HBox fila = new HBox(burbuja);
        fila.setAlignment(mio ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        fila.setPadding(new Insets(3, 12, 3, 12));
        HBox.setHgrow(burbuja, Priority.NEVER);
        setGraphic(fila);
        setText(null);
    }

    static String tickDe(String estado) {
        if ("LEIDO".equals(estado) || "ENTREGADO".equals(estado)) {
            return "✓✓";
        }
        if ("ERROR".equals(estado)) {
            return "!";
        }
        if ("PENDIENTE".equals(estado)) {
            return "…";
        }
        return "✓";
    }

    /** Se llama desde tareas de fondo; la escena FX nunca decodifica Base64. */
    public static void precalentar(String idMensaje, String base64) {
        if (idMensaje == null || base64 == null || base64.length() < 100
                || MINIATURAS.containsKey(idMensaje)) {
            return;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            if (bytes.length < 10) {
                return;
            }
            Image imagen = new Image(new ByteArrayInputStream(bytes), 300, 0, true, true);
            if (!imagen.isError()) {
                MINIATURAS.put(idMensaje, imagen);
            }
        } catch (Exception ignorada) {
            // La miniatura es best-effort; la burbuja conserva el placeholder.
        }
    }

    private static void abrirGrande(MensajeLocal item, Image miniatura) {
        ImageView grande = new ImageView(miniatura);
        grande.setPreserveRatio(true);
        grande.setFitWidth(900);
        grande.setFitHeight(720);
        Stage ventana = new Stage();
        ventana.setTitle(item.nombreArchivo() == null ? "Imagen" : item.nombreArchivo());
        ventana.setScene(new Scene(new javafx.scene.layout.StackPane(grande), 940, 760));
        ventana.show();
    }
}
