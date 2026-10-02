package universidad.mensajeria.server.almacenarinformacion;

import universidad.mensajeria.common.interno.ArchivoDTO;
import universidad.mensajeria.common.interno.AuditoriaInformeDTO;
import universidad.mensajeria.common.interno.FechasUsuarioDTO;
import universidad.mensajeria.common.interno.InformeFiltroDTO;
import universidad.mensajeria.common.interno.LimitesDTO;
import universidad.mensajeria.common.interno.LoginConteoDTO;
import universidad.mensajeria.common.interno.MensajeResumenDTO;
import universidad.mensajeria.common.interno.PaginaDTO;
import universidad.mensajeria.common.interno.TipoAccion;
import universidad.mensajeria.common.interno.UsuarioDTO;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * API publica del paquete (PLAN2 §2.5): el UNICO punto de acceso a
 * Persistencia. Firmas 100% DTO/primitivos; el adaptador JPA queda detrás
 * del puerto y no forma parte del contrato de este paquete.
 */
public interface InterfazAlmacenarInformacion {

    // ---- usuarios ----

    boolean existeUsuarioPorCodigo(String codigo);

    long contarUsuarios();

    Optional<UsuarioDTO> buscarUsuario(String codigo);

    List<UsuarioDTO> listarUsuarios();

    void registrarUsuarioNuevo(String codigo, String passwordHash, String nombres,
                               String apellidos, String programa);

    /** Sincroniza la semilla CSV al arrancar, conservando filas históricas en la BD. */
    void reconciliarUsuariosDesdeCsv(List<UsuarioDTO> usuariosCsv);

    // ---- mensajes ----

    long guardarMensajeTexto(String remitente, String destinatario, String contenido,
                             String hash, int caracteres, int palabras, String ip);

    long guardarMensajeImagen(String remitente, String destinatario, String nombreArchivo,
                              String rutaRelativa, long tamano, String tipo, String mime,
                              String hash, String etapasJson, String ip);

    long guardarMensajeArchivo(String remitente, String destinatario, String nombreArchivo,
                               String rutaRelativa, long tamano, String tipo, String mime,
                               String hash, String ip);

    PaginaDTO<MensajeResumenDTO> paginaConversacion(String codigoA, String codigoB,
                                                    int pagina, int tamano);

    List<MensajeResumenDTO> pendientesPara(String codigo, int limite);

    // ---- archivos ----

    ArchivoDTO registrarArchivo(String nombre, String ruta, long tamano, String tipo,
                                String mime, String hash, String codigoPropietario);

    default ArchivoDTO registrarImagen(String nombre, String ruta, long tamano, String mime,
                                       String hash, String codigoPropietario) {
        return registrarArchivo(nombre, ruta, tamano, "IMAGEN", mime, hash, codigoPropietario);
    }

    Optional<ArchivoDTO> buscarArchivoPorId(long id);

    Optional<ArchivoDTO> buscarArchivoPorHash(String hashSha256);

    /**
     * Bytes del archivo en uploads/ para previsualizar en la vista escritorio.
     * Por defecto no soportado (los mocks de tests lo ignoran).
     */
    default byte[] leerBytesArchivo(long archivoId) throws IOException {
        throw new IOException("lectura no soportada");
    }

    // ---- auditoria ----

    long registrarAccion(TipoAccion tipo, String descripcion,
                         String codigoUsuario, String ip, String detalles);

    // ---- informes (FASE 9, §8): consultas; el filtrado por fechas/codigo
    //      se resuelve aqui (JPQL) y el formateo puro en Servicios ----

    /** Informe 1: fechas de registro/ultima conexion por usuario. */
    List<FechasUsuarioDTO> fechasUsuarios();

    /** Informe 2: COUNT(*) GROUP BY usuario sobre registro_acciones. */
    List<LoginConteoDTO> conteoConexiones(InformeFiltroDTO filtro);

    /** Informe 3: historico global de mensajes con detalle. */
    List<MensajeResumenDTO> historicoMensajes(InformeFiltroDTO filtro);

    /** Informe 4: bitacora de auditoria (misma fuente que logs/server.log). */
    List<AuditoriaInformeDTO> auditoriaInforme(InformeFiltroDTO filtro);

    // ---- limites ----

    LimitesDTO leerLimites();

    // ---- ficheros (work/ -> uploads/) ----

    File guardarOriginalTexto(String contenido, String remitente) throws IOException;

    File guardarOriginalBinario(byte[] bytes, String remitente, String nombreSugerido)
            throws IOException;

    List<String> promocionarAUploads(String hash, File original, String nombreOriginal)
            throws IOException;

    default List<String> promocionarAUploads(String hash, File original, String nombreOriginal,
                                             String carpetaPropietario) throws IOException {
        return promocionarAUploads(hash, original, nombreOriginal, carpetaPropietario, null);
    }

    /**
     * Variante con subcarpeta intermedia (p. ej. "textos"):
     * {@code uploads/<propietario>/<subcarpeta>/<hash>/}. Sin propietario ni
     * subcarpeta conserva el legado {@code uploads/<hash>/}.
     */
    List<String> promocionarAUploads(String hash, File original, String nombreOriginal,
                                     String carpetaPropietario, String subcarpeta)
            throws IOException;
}
