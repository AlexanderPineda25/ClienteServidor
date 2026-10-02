package universidad.mensajeria.cliente.componentes.historial;

import universidad.mensajeria.cliente.transversal.records.MensajeLocal;
import universidad.mensajeria.cliente.transversal.records.PendienteEnvio;
import universidad.mensajeria.cliente.transversal.records.RecienteChat;

import java.util.List;
import java.util.Optional;

/**
 * Historial local (InterfazHistorialLocal → adaptador H2; §5.2/§5.4 en
 * miniatura: el puerto es neutro, el motor es intercambiable).
 */
public interface InterfazHistorialLocal {

    /** Selecciona/abre el almacenamiento de la cuenta antes de autenticarla. */
    default void prepararCuenta(String codigo) throws Exception {
    }

    void guardar(MensajeLocal mensaje) throws Exception;

    List<MensajeLocal> pagina(String remitente, String destinatario, int limite, int offset) throws Exception;

    int contar(String remitente, String destinatario) throws Exception;

    void marcarDescargado(String idMensaje, String rutaArchivo) throws Exception;

    void marcarEstado(String idMensaje, String estado) throws Exception;

    void marcarLeidosDe(String yo, String otro) throws Exception;

    List<String> idsNoLeidos(String yo, String otro) throws Exception;

    java.util.List<RecienteChat> recientes(String yo) throws Exception;

    /**
     * FASE 13.9: blob bajo demanda. La lista (`pagina`) ya no trae `contenido`
     * (13.8/13.9); los bytes solo se leen para la miniatura visible.
     */
    Optional<String> blobPorId(String idMensaje) throws Exception;

    Optional<String> archivoIdPorId(String idMensaje) throws Exception;

    void actualizarArchivoId(String idMensaje, String archivoId) throws Exception;

    /**
     * Guarda los bytes descargados bajo demanda (Fase 13.13, §7 HISTORIAL_LOCAL.md).
     */
    void actualizarContenido(String idMensaje, String base64) throws Exception;

    List<PendienteEnvio> pendientes() throws Exception;

    void registrarPendiente(PendienteEnvio pendiente) throws Exception;

    void incrementarIntento(String id, String ultimoError) throws Exception;

    void eliminarPendiente(String id) throws Exception;

    Optional<PendienteEnvio> pendientePorId(String id) throws Exception;
}
