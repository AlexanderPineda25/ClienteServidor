package universidad.mensajeria.server.transversal.fachada;

import universidad.mensajeria.common.interno.ArchivoDTO;
import universidad.mensajeria.common.interno.EstadoServidor;
import universidad.mensajeria.common.interno.InformeDTO;
import universidad.mensajeria.common.interno.InformeFiltroDTO;
import universidad.mensajeria.common.interno.MensajeResumenDTO;
import universidad.mensajeria.common.interno.UsuarioResumen;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Contrato transversal de la fachada (paquete transversal.fachada del diagrama).
 * La presentación depende SOLO de esta interfaz (DIP): vistas → Fachada.
 */
public interface Fachada {

    void iniciarServidor();

    void detenerServidor();

    EstadoServidor estadoServidor();

    List<String> usuariosConectados();

    List<UsuarioResumen> usuariosRegistrados();

    int mensajesEnCola();

    void difundirAdministrativo(String contenido);

    /**
     * Cierre administrativo de cualquier codigo, sesion o "*" (todas).
     * Devuelve sesiones cerradas; lanza IllegalArgumentException sin blancos.
     */
    int cerrarConexionAdmin(String objetivo, String motivo);

    String ipLocal();

    /** Eventos en vivo de LogDeEventos (req. #12: consola + escritorio). */
    void suscribirEventos(Consumer<String> observador);

    // ---- FASE 9 (§8): los 4 informes; la Fachada reúne y Servicios arma ----

    /** Informe 1: directorio de usuarios registrados [RF-S23]. */
    InformeDTO informeUsuarios(InformeFiltroDTO filtro);

    /** Informe 2: conectados y frecuencia de acceso [RF-S24]. */
    InformeDTO informeConexiones(InformeFiltroDTO filtro);

    /** Informe 3: histórico cronológico de mensajes [RF-S25]. */
    InformeDTO informeMensajes(InformeFiltroDTO filtro);

    /** Informe 4: bitácora de auditoría [RF-S26]. */
    InformeDTO informeAuditoria(InformeFiltroDTO filtro);

    /**
     * Detalle crudo de mensajes (contenido + hash + etapas) para la vista
     * escritorio: muestra texto completo, previsualiza imagenes y lista los
     * filtros aplicados sin pasar por el formateo resumido del Informe 3.
     */
    default List<MensajeResumenDTO> mensajesDetalle(InformeFiltroDTO filtro) {
        return List.of();
    }

    /** Metadatos del archivo asociado a un mensaje (para previsualizar). */
    default Optional<ArchivoDTO> archivoParaVista(long archivoId) {
        return Optional.empty();
    }

    /** Bytes del archivo asociado (miniatura en la vista escritorio). */
    default byte[] bytesArchivoParaVista(long archivoId) {
        throw new UnsupportedOperationException("descarga no disponible");
    }
}
