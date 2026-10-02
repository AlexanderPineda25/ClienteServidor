package universidad.mensajeria.server.almacenarinformacion;

import universidad.mensajeria.common.interno.ArchivoDTO;
import universidad.mensajeria.common.interno.ArchivoGuardarDTO;
import universidad.mensajeria.common.interno.AuditoriaInformeDTO;
import universidad.mensajeria.common.interno.FechasUsuarioDTO;
import universidad.mensajeria.common.interno.InformeFiltroDTO;
import universidad.mensajeria.common.interno.LimitesDTO;
import universidad.mensajeria.common.interno.LoginConteoDTO;
import universidad.mensajeria.common.interno.MensajeGuardarDTO;
import universidad.mensajeria.common.interno.MensajeResumenDTO;
import universidad.mensajeria.common.interno.PaginaDTO;
import universidad.mensajeria.common.interno.TipoAccion;
import universidad.mensajeria.common.interno.UsuarioDTO;

import java.util.List;
import java.util.Optional;

/** Puerto de persistencia. Su contrato contiene solo DTOs y valores escalares. */
public interface PersistenciaPort {

    UsuarioDTO guardarUsuario(UsuarioDTO usuario);

    Optional<UsuarioDTO> buscarUsuarioPorCodigo(String codigo);

    boolean existeUsuarioPorCodigo(String codigo);

    List<UsuarioDTO> listarUsuarios();

    long contarUsuarios();

    /** Sincroniza altas/cambios y desactiva bajas sin borrar referencias historicas. */
    void reconciliarUsuarios(List<UsuarioDTO> usuariosCsv);

    MensajeResumenDTO guardarMensaje(MensajeGuardarDTO mensaje);

    Optional<MensajeResumenDTO> buscarMensajePorId(long id);

    PaginaDTO<MensajeResumenDTO> paginaConversacion(long usuarioA, long usuarioB,
                                                    int pagina, int tamano);

    ArchivoDTO guardarArchivo(ArchivoGuardarDTO archivo);

    Optional<ArchivoDTO> buscarArchivoPorId(long id);

    Optional<ArchivoDTO> buscarArchivoPorHash(String hashSha256);

    Optional<ArchivoDTO> buscarArchivoPorPropietarioYHash(String codigoPropietario,
                                                           String hashSha256);

    long guardarAccion(TipoAccion tipo, String codigoUsuario, String descripcion,
                       String ip, String detalles);

    PaginaDTO<AuditoriaInformeDTO> paginaAcciones(TipoAccion tipo, int pagina, int tamano);

    LimitesDTO leerLimites();

    PaginaDTO<MensajeResumenDTO> paginaConversacionPorCodigo(String codigoA, String codigoB,
                                                             int pagina, int tamano);

    List<MensajeResumenDTO> mensajesPendientesPorCodigo(String codigo, int limite);

    List<AuditoriaInformeDTO> accionesInforme(InformeFiltroDTO filtro);

    List<LoginConteoDTO> conteoConexiones(InformeFiltroDTO filtro);

    List<MensajeResumenDTO> historicoMensajes(InformeFiltroDTO filtro);

    List<FechasUsuarioDTO> fechasUsuarios();
}
