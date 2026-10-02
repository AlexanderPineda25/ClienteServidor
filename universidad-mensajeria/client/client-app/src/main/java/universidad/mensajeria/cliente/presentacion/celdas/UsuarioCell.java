package universidad.mensajeria.cliente.presentacion.celdas;

import java.util.function.Function;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import universidad.mensajeria.cliente.transversal.records.RecienteChat;

/** Conversación del panel lateral: identidad, presencia, preview y no leídos. */
public final class UsuarioCell extends ListCell<RecienteChat> {

    private final Function<String, String> nombreVisible;

    public UsuarioCell(Function<String, String> nombreVisible) {
        this.nombreVisible = nombreVisible;
        getStyleClass().add("chat-list-cell");
    }

    @Override
    protected void updateItem(RecienteChat item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
            setGraphic(null);
            setText(null);
            return;
        }

        Circle punto = new Circle(5, item.conectado()
                ? Color.web("#35a978") : Color.web("#aab6c2"));
        StackPane avatar = new StackPane(punto);
        avatar.getStyleClass().add("presence-avatar");
        avatar.setPrefSize(38, 38);
        avatar.setMinSize(38, 38);
        Tooltip.install(avatar, new Tooltip(item.conectado() ? "En línea" : "Desconectado"));

        Label nombre = new Label(nombreVisible.apply(item.otro()));
        nombre.getStyleClass().add("chat-list-name");
        nombre.setMaxWidth(Double.MAX_VALUE);
        nombre.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        Tooltip.install(nombre, new Tooltip(nombre.getText()));
        String preview = item.ultimoContenido() == null || item.ultimoContenido().isBlank()
                ? "Sin mensajes aún" : item.ultimoContenido().replaceAll("\\s+", " ");
        if (preview.length() > 54) {
            preview = preview.substring(0, 53) + "…";
        }
        Label detalle = new Label(preview);
        detalle.getStyleClass().add("chat-list-preview");
        detalle.setMaxWidth(Double.MAX_VALUE);
        detalle.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        VBox textos = new VBox(4, nombre, detalle);
        HBox.setHgrow(textos, Priority.ALWAYS);

        Region espacio = new Region();
        HBox.setHgrow(espacio, Priority.ALWAYS);
        Label badge = new Label(item.noLeidos() > 0 ? String.valueOf(item.noLeidos()) : "");
        badge.getStyleClass().add("unread-badge");
        badge.setVisible(item.noLeidos() > 0);
        badge.setManaged(item.noLeidos() > 0);

        HBox fila = new HBox(10, avatar, textos, espacio, badge);
        fila.setAlignment(Pos.CENTER_LEFT);
        fila.getStyleClass().add("chat-list-row");
        setGraphic(fila);
        setText(null);
    }
}
