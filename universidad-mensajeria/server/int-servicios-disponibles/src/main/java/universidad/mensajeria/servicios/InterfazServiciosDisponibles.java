package universidad.mensajeria.servicios;

import universidad.mensajeria.common.interno.AuditoriaInformeDTO;
import universidad.mensajeria.common.interno.ConexionInformeDTO;
import universidad.mensajeria.common.interno.InformeDTO;
import universidad.mensajeria.common.interno.InformeFiltroDTO;
import universidad.mensajeria.common.interno.LimitesDTO;
import universidad.mensajeria.common.interno.MensajeResumenDTO;
import universidad.mensajeria.common.interno.UsuarioInformeDTO;

import java.util.List;

/**
 * Reglas puras sobre DTOs (PLAN2 §2.5 Regla 6): sin red, sin config, sin BD.
 * Los limites estaticos los recibe de Fabrica desde server.properties.
 * FASE 9 (§8): aqui se ARMAN los 4 informes a partir de los datos que la
 * Fachada reuno por sus flechas (logica pura: filtrar + formatear).
 */
public interface InterfazServiciosDisponibles {

    boolean puedeIniciarSesion(String codigo, int sesionesDelCodigo, int sesionesTotales,
                               LimitesDTO limites);

    int maxConexiones(LimitesDTO limites);

    int maxConexionesPorUsuario(LimitesDTO limites);

    /** Valida tamano de subida; lanza IllegalArgumentException si excede. */
    void validarTamanoArchivo(long tamanoBytes, long maximoBytes);

    // ---- FASE 9: armado puro de los 4 informes (§8) ----

    InformeDTO armarInformeUsuarios(List<UsuarioInformeDTO> usuarios, InformeFiltroDTO filtro);

    InformeDTO armarInformeConexiones(List<ConexionInformeDTO> conexiones, InformeFiltroDTO filtro);

    InformeDTO armarInformeMensajes(List<MensajeResumenDTO> mensajes, InformeFiltroDTO filtro);

    InformeDTO armarInformeAuditoria(List<AuditoriaInformeDTO> acciones, InformeFiltroDTO filtro);
}
