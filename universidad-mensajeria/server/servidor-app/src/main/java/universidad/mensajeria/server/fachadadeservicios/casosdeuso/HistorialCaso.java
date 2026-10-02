package universidad.mensajeria.server.fachadadeservicios.casosdeuso;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.interno.MensajeResumenDTO;
import universidad.mensajeria.common.interno.PaginaDTO;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.common.tipos.TipoMensaje;
import universidad.mensajeria.protocolo.InterfazProtocoloComunicacion;
import universidad.mensajeria.server.almacenarinformacion.InterfazAlmacenarInformacion;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HISTORIAL_REQ (§4.2): pagina de conversacion SIN bytes (contenidoImagen
 * nunca viaja en la pagina). Tamano fijo 50 (contrato PROTOCOLO.md §3).
 */
public final class HistorialCaso {

    private static final Logger LOG = LoggerFactory.getLogger(HistorialCaso.class);
    private static final int TAMANO_PAGINA = 50;
    private static final Gson JSON = new Gson();

    private final InterfazAlmacenarInformacion almacenar;
    private final InterfazProtocoloComunicacion protocolo;
    private final ResponderCaso respuestas;

    public HistorialCaso(InterfazAlmacenarInformacion almacenar,
                         InterfazProtocoloComunicacion protocolo,
                         ResponderCaso respuestas) {
        this.almacenar = almacenar;
        this.protocolo = protocolo;
        this.respuestas = respuestas;
    }

    public void historial(IdSesion sesion, String otro, int pagina, String idSolicitud) {
        String remitente = sesion.codigo();
        PaginaDTO<MensajeResumenDTO> page =
                almacenar.paginaConversacion(remitente, otro, pagina, TAMANO_PAGINA);
        List<Map<String, Object>> resumen = page.contenido().stream()
                .map(HistorialCaso::aResumen)
                .toList();
        try {
            protocolo.enviar(sesion, Mensaje.builder()
                    .tipo(TipoMensaje.HISTORIAL_PAGE)
                    .id(idSolicitud)
                    .fechaHora(LocalDateTime.now().toString())
                    .pagina(pagina)
                    .totalPaginas(page.totalPaginas())
                    .contenido(JSON.toJson(resumen))
                    .exito(true)
                    .build());
        } catch (Exception e) {
            LOG.warn("No se pudo enviar historial a {}: {}", remitente, e.getMessage());
        }
        LOG.info("Historial {} <-> {} pagina {}/{} ({} mensajes)", remitente, otro,
                pagina, page.totalPaginas(), resumen.size());
    }

    /** Resumen de un mensaje SIN bytes de imagen (historial ligero, sin lazy). */
    static Map<String, Object> aResumen(MensajeResumenDTO m) {
        Map<String, Object> fila = new LinkedHashMap<>();
        fila.put("id", m.id());
        fila.put("remitente", m.remitente());
        fila.put("destinatario", m.destinatario());
        fila.put("tipo", m.tipo());
        if (m.contenido() != null) {
            fila.put("contenido", m.contenido());
        }
        if (m.hashSha256() != null) {
            fila.put("hashSha256", m.hashSha256());
        }
        if (m.numCaracteres() != null) {
            fila.put("numCaracteres", m.numCaracteres());
        }
        if (m.numPalabras() != null) {
            fila.put("numPalabras", m.numPalabras());
        }
        if (m.nombreArchivo() != null) {
            fila.put("nombreArchivo", m.nombreArchivo());
        }
        if (m.tamanoArchivo() != null) {
            fila.put("tamanoArchivo", m.tamanoArchivo());
        }
        if (m.archivoId() != null) {
            fila.put("archivoId", String.valueOf(m.archivoId()));
        }
        if (m.mime() != null) {
            fila.put("mime", m.mime());
        }
        if (m.etapasFiltrado() != null) {
            fila.put("etapasFiltrado", m.etapasFiltrado());
        }
        fila.put("fechaEnvio", m.fechaEnvio());
        return fila;
    }
}
