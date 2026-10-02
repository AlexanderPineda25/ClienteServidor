package universidad.mensajeria.server.almacenarinformacion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * UNICO punto de acceso a Persistencia (PLAN2 §2.2, E15 por puerto).
 * Coordina almacenamiento y archivos de trabajo sin exponer infraestructura JPA.
 * Sin anotaciones de framework (lo construye Fabrica).
 */
public class AlmacenarInformacion implements InterfazAlmacenarInformacion {

    private static final Logger LOG = LoggerFactory.getLogger(AlmacenarInformacion.class);

    private final PersistenciaPort persistencia;
    private final Path trabajoBase;
    private final Path uploadDir;

    public AlmacenarInformacion(PersistenciaPort persistencia, Path trabajoBase, Path uploadDir) {
        this.persistencia = persistencia;
        this.trabajoBase = trabajoBase;
        this.uploadDir = uploadDir;
    }

    // ---- usuarios ----

    @Override
    public boolean existeUsuarioPorCodigo(String codigo) {
        return persistencia.existeUsuarioPorCodigo(codigo);
    }

    @Override
    public long contarUsuarios() {
        return persistencia.contarUsuarios();
    }

    @Override
    public Optional<UsuarioDTO> buscarUsuario(String codigo) {
        return persistencia.buscarUsuarioPorCodigo(codigo);
    }

    @Override
    public List<UsuarioDTO> listarUsuarios() {
        return persistencia.listarUsuarios();
    }

    @Override
    public void reconciliarUsuariosDesdeCsv(List<UsuarioDTO> usuariosCsv) {
        persistencia.reconciliarUsuarios(usuariosCsv);
    }

    @Override
    public void registrarUsuarioNuevo(String codigo, String passwordHash, String nombres,
                                      String apellidos, String programa) {
        persistencia.guardarUsuario(new UsuarioDTO(codigo.trim(), nombres.trim(),
                apellidos.trim(), programa.trim(), passwordHash, true));
    }

    // ---- mensajes ----

    @Override
    public long guardarMensajeTexto(String remitente, String destinatario, String contenido,
                                    String hash, int caracteres, int palabras, String ip) {
        MensajeResumenDTO guardado = persistencia.guardarMensaje(new MensajeGuardarDTO(
                remitente, destinatario, "TEXTO", contenido, hash, caracteres, palabras,
                null, null, ip));
        return guardado.id();
    }

    @Override
    public long guardarMensajeImagen(String remitente, String destinatario, String nombreArchivo,
                                     String rutaRelativa, long tamano, String tipo, String mime,
                                     String hash, String etapasJson, String ip) {
        ArchivoDTO archivo = registrarImagen(nombreArchivo, rutaRelativa, tamano, mime,
                hash, remitente);
        MensajeResumenDTO guardado = persistencia.guardarMensaje(new MensajeGuardarDTO(
                remitente, destinatario, "IMAGEN", null, hash, null, null,
                archivo.id(), etapasJson, ip));
        return guardado.id();
    }

    @Override
    public long guardarMensajeArchivo(String remitente, String destinatario, String nombreArchivo,
                                      String rutaRelativa, long tamano, String tipo, String mime,
                                      String hash, String ip) {
        ArchivoDTO archivo = registrarArchivo(nombreArchivo, rutaRelativa, tamano, tipo, mime,
                hash, remitente);
        MensajeResumenDTO guardado = persistencia.guardarMensaje(new MensajeGuardarDTO(
                remitente, destinatario, "ARCHIVO", null, hash, null, null,
                archivo.id(), null, ip));
        return guardado.id();
    }

    @Override
    public PaginaDTO<MensajeResumenDTO> paginaConversacion(String codigoA, String codigoB,
                                                           int pagina, int tamano) {
        return persistencia.paginaConversacionPorCodigo(codigoA, codigoB, pagina, tamano);
    }

    @Override
    public List<MensajeResumenDTO> pendientesPara(String codigo, int limite) {
        return persistencia.mensajesPendientesPorCodigo(codigo, limite);
    }

    // ---- archivos ----

    @Override
    public ArchivoDTO registrarArchivo(String nombre, String ruta, long tamano, String tipo,
                                       String mime, String hash, String codigoPropietario) {
        Optional<ArchivoDTO> existente = persistencia.buscarArchivoPorHash(hash);
        if (existente.isPresent()) {
            return existente.get();
        }
        return persistencia.guardarArchivo(new ArchivoGuardarDTO(nombre, ruta, tamano, tipo,
                mime, hash, codigoPropietario));
    }

    @Override
    public ArchivoDTO registrarImagen(String nombre, String ruta, long tamano, String mime,
                                      String hash, String codigoPropietario) {
        Optional<ArchivoDTO> existente = persistencia
                .buscarArchivoPorPropietarioYHash(codigoPropietario, hash);
        if (existente.isPresent()) {
            return existente.get();
        }
        return persistencia.guardarArchivo(new ArchivoGuardarDTO(nombre, ruta, tamano,
                "IMAGEN", mime, hash, codigoPropietario));
    }

    @Override
    public Optional<ArchivoDTO> buscarArchivoPorId(long id) {
        return persistencia.buscarArchivoPorId(id);
    }

    @Override
    public Optional<ArchivoDTO> buscarArchivoPorHash(String hashSha256) {
        return persistencia.buscarArchivoPorHash(hashSha256);
    }

    // ---- auditoria ----

    @Override
    public long registrarAccion(TipoAccion tipo, String descripcion,
                                String codigoUsuario, String ip, String detalles) {
        return persistencia.guardarAccion(tipo, codigoUsuario, descripcion, ip, detalles);
    }

    // ---- limites ----

    @Override
    public LimitesDTO leerLimites() {
        return persistencia.leerLimites();
    }

    // ---- informes (FASE 9, §8) ----

    @Override
    public List<FechasUsuarioDTO> fechasUsuarios() {
        return persistencia.fechasUsuarios();
    }

    @Override
    public List<LoginConteoDTO> conteoConexiones(InformeFiltroDTO filtro) {
        // "Veces conectado" = logins exitosos (mismo criterio que actualiza
        // fecha_ultima_conexion en registrarAccion).
        return persistencia.conteoConexiones(filtro);
    }

    @Override
    public List<MensajeResumenDTO> historicoMensajes(InformeFiltroDTO filtro) {
        return persistencia.historicoMensajes(filtro);
    }

    @Override
    public List<AuditoriaInformeDTO> auditoriaInforme(InformeFiltroDTO filtro) {
        return persistencia.accionesInforme(filtro);
    }

    // ---- ficheros (work/ -> uploads/) ----

    @Override
    public File guardarOriginalTexto(String contenido, String remitente) throws IOException {
        Path carpeta = nuevaCarpetaTrabajo(remitente);
        Path destino = carpeta.resolve("original.txt");
        Files.writeString(destino, contenido, StandardCharsets.UTF_8);
        return destino.toFile();
    }

    @Override
    public File guardarOriginalBinario(byte[] bytes, String remitente, String nombreSugerido)
            throws IOException {
        Path carpeta = nuevaCarpetaTrabajo(remitente);
        String nombre = (nombreSugerido == null || nombreSugerido.isBlank())
                ? "original.bin" : nombreSugerido.replaceAll("[^A-Za-z0-9._-]", "_");
        Path destino = carpeta.resolve(nombre);
        Files.write(destino, bytes);
        return destino.toFile();
    }

    @Override
    public List<String> promocionarAUploads(String hash, File original, String nombreOriginal)
            throws IOException {
        return promocionarAUploads(hash, original, nombreOriginal, null);
    }

    @Override
    public List<String> promocionarAUploads(String hash, File original, String nombreOriginal,
                                            String carpetaPropietario) throws IOException {
        return promocionarAUploads(hash, original, nombreOriginal, carpetaPropietario, null);
    }

    @Override
    public List<String> promocionarAUploads(String hash, File original, String nombreOriginal,
                                            String carpetaPropietario, String subcarpeta)
            throws IOException {
        String propietario = carpetaPropietario == null || carpetaPropietario.isBlank()
                ? null : segmentoSeguro(carpetaPropietario);
        String sub = subcarpeta == null || subcarpeta.isBlank()
                ? null : subcarpeta.trim().replaceAll("[^A-Za-z0-9_-]", "_");
        Path carpeta = propietario == null
                ? uploadDir.resolve(hash)
                : sub == null ? uploadDir.resolve(propietario).resolve(hash)
                : uploadDir.resolve(propietario).resolve(sub).resolve(hash);
        Files.createDirectories(carpeta);
        List<String> rutas = new ArrayList<>();
        String extension = extensionDe(nombreOriginal);
        Path destinoOriginal = carpeta.resolve("00_original" + extension);
        Files.move(original.toPath(), destinoOriginal, StandardCopyOption.REPLACE_EXISTING);
        String prefijo = propietario == null ? hash
                : sub == null ? propietario + "/" + hash
                : propietario + "/" + sub + "/" + hash;
        rutas.add(prefijo + "/00_original" + extension);
        // La tuberia retorna solo la ultima salida: los derivados 01..05 se
        // recogen del directorio de trabajo (estan nombrados 0N_*.png).
        List<Path> enTrabajo;
        try (var flujos = Files.list(original.toPath().getParent())) {
            enTrabajo = flujos
                    .filter(p -> p.getFileName().toString().matches("0[1-9]_.*\\.png"))
                    .sorted()
                    .toList();
        }
        for (Path derivado : enTrabajo) {
            Path destino = carpeta.resolve(derivado.getFileName().toString());
            Files.move(derivado, destino, StandardCopyOption.REPLACE_EXISTING);
            rutas.add(prefijo + "/" + derivado.getFileName());
        }
        LOG.info("Promocionados {} archivos a uploads/{}", rutas.size(), prefijo);
        return List.copyOf(rutas);
    }

    private Path nuevaCarpetaTrabajo(String remitente) throws IOException {
        String seguro = remitente == null ? "anonimo" : remitente.replaceAll("[^A-Za-z0-9_-]", "_");
        Path carpeta = trabajoBase.resolve(seguro + "-" + UUID.randomUUID());
        Files.createDirectories(carpeta);
        return carpeta;
    }

    private static String extensionDe(String nombre) {
        if (nombre == null) {
            return ".bin";
        }
        int punto = nombre.lastIndexOf('.');
        if (punto < 0 || punto == nombre.length() - 1) {
            return nombre.endsWith(".txt") ? ".txt" : ".bin";
        }
        String extension = nombre.substring(punto).toLowerCase();
        return extension.matches("\\.[a-z0-9]{1,5}") ? extension : ".bin";
    }

    private static String segmentoSeguro(String nombre) {
        String seguro = nombre.trim().replaceAll("[^\\p{L}\\p{N} _\\[\\]-]", "_")
                .replaceAll("\\s+", " ").replaceAll("[. ]+$", "");
        return seguro.isBlank() ? "usuario" : seguro;
    }
}
