package universidad.mensajeria.server.persistencia;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
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
import universidad.mensajeria.common.interno.UsuarioNoEncontradoException;
import universidad.mensajeria.server.almacenarinformacion.PersistenciaPort;
import universidad.mensajeria.server.persistencia.entidades.Archivo;
import universidad.mensajeria.server.persistencia.entidades.ConfiguracionLimites;
import universidad.mensajeria.server.persistencia.entidades.EstadoUsuario;
import universidad.mensajeria.server.persistencia.entidades.Mensaje;
import universidad.mensajeria.server.persistencia.entidades.RegistroAccion;
import universidad.mensajeria.server.persistencia.entidades.TipoContenido;
import universidad.mensajeria.server.persistencia.entidades.Usuario;
import universidad.mensajeria.server.persistencia.repositorio.ArchivoRepository;
import universidad.mensajeria.server.persistencia.repositorio.ConfiguracionLimitesRepository;
import universidad.mensajeria.server.persistencia.repositorio.MensajeRepository;
import universidad.mensajeria.server.persistencia.repositorio.RegistroAccionRepository;
import universidad.mensajeria.server.persistencia.repositorio.UsuarioRepository;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Traduce el puerto plano a entidades y consultas JPA dentro de persistencia. */
@Component
@Transactional
public class JpaPersistenciaAdapter implements PersistenciaPort {

    private final UsuarioRepository usuarios;
    private final MensajeRepository mensajes;
    private final ArchivoRepository archivos;
    private final RegistroAccionRepository acciones;
    private final ConfiguracionLimitesRepository limites;

    public JpaPersistenciaAdapter(UsuarioRepository usuarios, MensajeRepository mensajes,
                                  ArchivoRepository archivos,
                                  RegistroAccionRepository acciones,
                                  ConfiguracionLimitesRepository limites) {
        this.usuarios = usuarios;
        this.mensajes = mensajes;
        this.archivos = archivos;
        this.acciones = acciones;
        this.limites = limites;
    }

    @Override
    public UsuarioDTO guardarUsuario(UsuarioDTO datos) {
        Optional<Usuario> existente = usuarios.findByCodigo(datos.codigo());
        Usuario usuario = existente.orElseGet(Usuario::new);
        if (existente.isEmpty()) {
            usuario.setEstado(EstadoUsuario.ACEPTADO);
        }
        aplicar(datos, usuario);
        return aDTO(usuarios.save(usuario));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UsuarioDTO> buscarUsuarioPorCodigo(String codigo) {
        return usuarios.findByCodigo(codigo).map(JpaPersistenciaAdapter::aDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existeUsuarioPorCodigo(String codigo) {
        return usuarios.existsByCodigo(codigo);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UsuarioDTO> listarUsuarios() {
        return usuarios.findAllByOrderByApellidosAsc().stream()
                .map(JpaPersistenciaAdapter::aDTO).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long contarUsuarios() {
        return usuarios.count();
    }

    @Override
    public void reconciliarUsuarios(List<UsuarioDTO> usuariosCsv) {
        if (usuariosCsv == null || usuariosCsv.isEmpty()) {
            throw new IllegalArgumentException("La semilla CSV no puede estar vacia");
        }
        Set<String> codigosActivos = new HashSet<>();
        for (UsuarioDTO datos : usuariosCsv) {
            if (datos == null || datos.codigo() == null || datos.codigo().isBlank()
                    || !codigosActivos.add(datos.codigo().trim())) {
                throw new IllegalArgumentException("Codigo de usuario CSV vacio o duplicado");
            }
        }
        for (UsuarioDTO datos : usuariosCsv) {
            Usuario usuario = usuarios.findByCodigo(datos.codigo().trim()).orElseGet(Usuario::new);
            aplicar(datos, usuario);
            usuario.setActivo(true);
            usuario.setEstado(EstadoUsuario.ACEPTADO);
            usuarios.save(usuario);
        }
        usuarios.findAll().stream()
                .filter(usuario -> !codigosActivos.contains(usuario.getCodigo()))
                .filter(Usuario::isActivo)
                .forEach(usuario -> {
                    usuario.setActivo(false);
                    usuarios.save(usuario);
                });
    }

    @Override
    public MensajeResumenDTO guardarMensaje(MensajeGuardarDTO datos) {
        Usuario remitente = usuarioRequerido(datos.remitente());
        Usuario destinatario = usuarioRequerido(datos.destinatario());
        TipoContenido tipo = TipoContenido.valueOf(datos.tipo());
        Mensaje mensaje = switch (tipo) {
            case TEXTO -> Mensaje.deTexto(remitente, destinatario, datos.contenido(),
                    datos.hashSha256(), requerido(datos.numCaracteres(), "numCaracteres"),
                    requerido(datos.numPalabras(), "numPalabras"), datos.ipRemitente());
            case IMAGEN -> Mensaje.deImagen(remitente, destinatario,
                    archivoRequerido(requerido(datos.archivoId(), "archivoId")),
                    datos.hashSha256(), datos.etapasFiltrado(), datos.ipRemitente());
            case ARCHIVO -> Mensaje.deArchivo(remitente, destinatario,
                    archivoRequerido(requerido(datos.archivoId(), "archivoId")),
                    datos.hashSha256(), datos.ipRemitente());
        };
        return aResumen(mensajes.save(mensaje));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MensajeResumenDTO> buscarMensajePorId(long id) {
        return mensajes.buscarPorIdConArchivo(id).map(JpaPersistenciaAdapter::aResumen);
    }

    @Override
    @Transactional(readOnly = true)
    public PaginaDTO<MensajeResumenDTO> paginaConversacion(long usuarioA, long usuarioB,
                                                          int pagina, int tamano) {
        return pagina(mensajes.paginaConversacion(usuarioA, usuarioB, page(pagina, tamano)),
                JpaPersistenciaAdapter::aResumen);
    }

    @Override
    public ArchivoDTO guardarArchivo(ArchivoGuardarDTO datos) {
        Usuario propietario = usuarioRequerido(datos.codigoPropietario());
        Archivo archivo = new Archivo(datos.nombre(), datos.ruta(), datos.tamano(), datos.tipo(),
                datos.mime(), datos.hashSha256(), propietario);
        return aDTO(archivos.save(archivo));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ArchivoDTO> buscarArchivoPorId(long id) {
        return archivos.findById(id).map(JpaPersistenciaAdapter::aDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ArchivoDTO> buscarArchivoPorHash(String hashSha256) {
        return archivos.findFirstByHashSha256(hashSha256).map(JpaPersistenciaAdapter::aDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ArchivoDTO> buscarArchivoPorPropietarioYHash(String codigoPropietario,
                                                                  String hashSha256) {
        return archivos.findFirstByPropietario_CodigoAndHashSha256(codigoPropietario, hashSha256)
                .map(JpaPersistenciaAdapter::aDTO);
    }

    @Override
    public long guardarAccion(TipoAccion tipo, String codigoUsuario, String descripcion,
                              String ip, String detalles) {
        Usuario usuario = codigoUsuario == null ? null : usuarios.findByCodigo(codigoUsuario).orElse(null);
        if (usuario != null && tipo == TipoAccion.LOGIN) {
            usuario.setFechaUltimaConexion(LocalDateTime.now());
            usuarios.save(usuario);
        }
        return acciones.save(new RegistroAccion(tipo, usuario, descripcion, ip, detalles)).getId();
    }

    @Override
    @Transactional(readOnly = true)
    public PaginaDTO<AuditoriaInformeDTO> paginaAcciones(TipoAccion tipo, int pagina, int tamano) {
        Page<RegistroAccion> resultado = tipo == null
                ? acciones.findAllByOrderByFechaDesc(page(pagina, tamano))
                : acciones.findByTipoOrderByFechaDesc(tipo, page(pagina, tamano));
        return pagina(resultado, JpaPersistenciaAdapter::aAuditoria);
    }

    @Override
    @Transactional(readOnly = true)
    public LimitesDTO leerLimites() {
        ConfiguracionLimites configuracion = limites.findFirstByActivaTrue()
                .orElseGet(ConfiguracionLimites::new);
        return new LimitesDTO(configuracion.getMaxConexionesTotales(),
                configuracion.getMaxConexionesUsuario(), configuracion.getMaxTamanoArchivo());
    }

    @Override
    @Transactional(readOnly = true)
    public PaginaDTO<MensajeResumenDTO> paginaConversacionPorCodigo(String codigoA,
                                                                   String codigoB,
                                                                   int pagina, int tamano) {
        return pagina(mensajes.paginaConversacionPorCodigo(codigoA, codigoB, page(pagina, tamano)),
                JpaPersistenciaAdapter::aResumen);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MensajeResumenDTO> mensajesPendientesPorCodigo(String codigo, int limite) {
        return mensajes.mensajesPendientesPorCodigo(codigo, page(0, limite)).getContent().stream()
                .map(JpaPersistenciaAdapter::aResumen).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditoriaInformeDTO> accionesInforme(InformeFiltroDTO filtro) {
        return acciones.buscarParaInforme(desde(filtro), hasta(filtro), codigo(filtro)).stream()
                .map(JpaPersistenciaAdapter::aAuditoria).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LoginConteoDTO> conteoConexiones(InformeFiltroDTO filtro) {
        return acciones.conteoConexiones(List.of(TipoAccion.LOGIN),
                desde(filtro), hasta(filtro)).stream()
                .map(fila -> new LoginConteoDTO(fila.getCodigo(), fila.getVeces())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MensajeResumenDTO> historicoMensajes(InformeFiltroDTO filtro) {
        return mensajes.historico(desde(filtro), hasta(filtro), codigo(filtro)).stream()
                .map(JpaPersistenciaAdapter::aResumen).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<FechasUsuarioDTO> fechasUsuarios() {
        return usuarios.findAllByOrderByApellidosAsc().stream()
                .map(usuario -> new FechasUsuarioDTO(usuario.getCodigo(),
                        texto(usuario.getFechaRegistro()), texto(usuario.getFechaUltimaConexion())))
                .toList();
    }

    private Usuario usuarioRequerido(String codigo) {
        return usuarios.findByCodigo(codigo).orElseThrow(() -> new UsuarioNoEncontradoException(codigo));
    }

    private Archivo archivoRequerido(long id) {
        return archivos.findById(id)
                .orElseThrow(() -> new IllegalStateException("Archivo inexistente: " + id));
    }

    private static void aplicar(UsuarioDTO datos, Usuario usuario) {
        usuario.setCodigo(datos.codigo().trim());
        usuario.setNombres(datos.nombres().trim());
        usuario.setApellidos(datos.apellidos().trim());
        usuario.setProgramaAcademico(datos.programa().trim());
        usuario.setPasswordHash(datos.hashContrasena());
        usuario.setActivo(datos.activo());
    }

    private static UsuarioDTO aDTO(Usuario usuario) {
        return new UsuarioDTO(usuario.getCodigo(), usuario.getNombres(), usuario.getApellidos(),
                usuario.getProgramaAcademico(), usuario.getPasswordHash(), usuario.isActivo());
    }

    private static ArchivoDTO aDTO(Archivo archivo) {
        return new ArchivoDTO(archivo.getId(), archivo.getNombre(), archivo.getRuta(),
                archivo.getTamano(), archivo.getTipo(), archivo.getMime(), archivo.getHashSha256());
    }

    private static MensajeResumenDTO aResumen(Mensaje mensaje) {
        Archivo archivo = mensaje.getArchivo();
        return new MensajeResumenDTO(mensaje.getId(), mensaje.getRemitente().getCodigo(),
                mensaje.getDestinatario().getCodigo(), mensaje.getTipo().name(),
                mensaje.getContenido(), mensaje.getHashSha256(), mensaje.getNumCaracteres(),
                mensaje.getNumPalabras(), archivo == null ? null : archivo.getNombre(),
                archivo == null ? null : archivo.getTamano(),
                archivo == null ? null : archivo.getId(), archivo == null ? null : archivo.getMime(),
                mensaje.getEtapasFiltrado(), texto(mensaje.getFechaEnvio()),
                mensaje.getRemitente().getNombres(), mensaje.getRemitente().getApellidos(),
                mensaje.getDestinatario().getNombres(), mensaje.getDestinatario().getApellidos());
    }

    private static AuditoriaInformeDTO aAuditoria(RegistroAccion accion) {
        return new AuditoriaInformeDTO(accion.getId(), accion.getTipo().name(),
                accion.getUsuario() == null ? null : accion.getUsuario().getCodigo(),
                accion.getDescripcion(), texto(accion.getFecha()), accion.getIp(),
                accion.getUsuario() == null ? null : accion.getUsuario().getNombres(),
                accion.getUsuario() == null ? null : accion.getUsuario().getApellidos());
    }

    private static <T, R> PaginaDTO<R> pagina(Page<T> pagina,
                                               java.util.function.Function<T, R> mapeador) {
        return new PaginaDTO<>(pagina.getContent().stream().map(mapeador).toList(),
                pagina.getNumber(), pagina.getTotalPages());
    }

    private static Pageable page(int pagina, int tamano) {
        if (pagina < 0 || tamano < 1) {
            throw new IllegalArgumentException("Paginacion invalida");
        }
        return PageRequest.of(pagina, tamano);
    }

    private static int requerido(Integer valor, String campo) {
        if (valor == null) {
            throw new IllegalArgumentException(campo + " es obligatorio");
        }
        return valor;
    }

    private static long requerido(Long valor, String campo) {
        if (valor == null) {
            throw new IllegalArgumentException(campo + " es obligatorio");
        }
        return valor;
    }

    private static String texto(LocalDateTime fecha) {
        return fecha == null ? null : fecha.toString();
    }

    private static LocalDateTime desde(InformeFiltroDTO filtro) {
        if (filtro == null || filtro.desde() == null || filtro.desde().isBlank()) {
            return LocalDateTime.of(1970, 1, 1, 0, 0);
        }
        return aFechaHora(filtro.desde(), false);
    }

    private static LocalDateTime hasta(InformeFiltroDTO filtro) {
        if (filtro == null || filtro.hasta() == null || filtro.hasta().isBlank()) {
            return LocalDateTime.of(9999, 12, 31, 23, 59, 59);
        }
        return aFechaHora(filtro.hasta(), true);
    }

    private static String codigo(InformeFiltroDTO filtro) {
        return filtro == null || filtro.codigo() == null ? "" : filtro.codigo().trim();
    }

    private static LocalDateTime aFechaHora(String texto, boolean finDeDia) {
        try {
            return LocalDateTime.parse(texto);
        } catch (DateTimeParseException ignorada) {
            try {
                LocalDate dia = LocalDate.parse(texto);
                return finDeDia ? dia.atTime(23, 59, 59) : dia.atStartOfDay();
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(
                        "fecha invalida (use yyyy-MM-dd o yyyy-MM-ddTHH:mm): " + texto);
            }
        }
    }
}
