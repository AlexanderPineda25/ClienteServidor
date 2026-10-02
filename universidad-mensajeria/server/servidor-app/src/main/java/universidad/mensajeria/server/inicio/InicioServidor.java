package universidad.mensajeria.server.inicio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import universidad.mensajeria.common.interno.LimitesDTO;
import universidad.mensajeria.common.interno.UsuarioDTO;

import universidad.mensajeria.protocolo.ConfigRed;

import universidad.mensajeria.server.almacenarinformacion.PersistenciaPort;
import universidad.mensajeria.server.vistaconsola.VistaConsola;
import universidad.mensajeria.server.vistaescritorio.VistaEscritorio;
import universidad.mensajeria.server.transversal.config.ArchivoProperties;
import universidad.mensajeria.server.transversal.config.DbProperties;
import universidad.mensajeria.server.transversal.config.FiltrosProperties;
import universidad.mensajeria.server.transversal.config.PoolProperties;
import universidad.mensajeria.server.transversal.config.RedProperties;
import universidad.mensajeria.server.transversal.config.SesionProperties;
import universidad.mensajeria.usuarios.InterfazUsuariosDisponibles;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * InicioServidor (boot del diagrama §2.1, paquete inicio): monta con Fabrica
 * (raiz de composicion de infraestructura), siembra, arranca el TCP y las DOS vistas
 * (VistaConsola + VistaEscritorio, solo hablan con Fachada).
 *
 * Propiedades de control:
 *   app.once=true      → migra+siembra+resumen y sale (verificacion multi-motor),
 *   app.inicio=false   → no abre el puerto (pruebas),
 *   app.consola=false  → no arranca VistaConsola (pruebas/CI),
 *   app.escritorio=false → no arranca VistaEscritorio (pruebas/CI).
 *   app.salir=false    → no llama System.exit (pruebas: el runner solo monta). */
@Component
public class InicioServidor implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(InicioServidor.class);

    private final PersistenciaPort persistencia;
    private final PasswordEncoder passwordEncoder;
    private final RedProperties red;
    private final SesionProperties sesion;
    private final ArchivoProperties archivo;
    private final FiltrosProperties filtros;
    private final DbProperties db;
    private final PoolProperties pool;
    private final ResourceLoader resources;
    private final ConfigurableApplicationContext contexto;

    private final boolean unaVez;
    private final boolean autoInicio;
    private final boolean conConsola;
    private final boolean conEscritorio;
    private final boolean salirAlTerminar;
    private final String semilla;

    private int codigoSalida;

    public InicioServidor(PersistenciaPort persistencia,
                          PasswordEncoder passwordEncoder,
                          RedProperties red,
                          SesionProperties sesion,
                          ArchivoProperties archivo,
                          FiltrosProperties filtros,
                          DbProperties db,
                          PoolProperties pool,
                          ResourceLoader resources,
                          ConfigurableApplicationContext contexto,
                          @Value("${app.once:false}") boolean unaVez,
                          @Value("${app.inicio:true}") boolean autoInicio,
                          @Value("${app.consola:true}") boolean conConsola,
                          @Value("${app.escritorio:true}") boolean conEscritorio,
                          @Value("${app.salir:true}") boolean salirAlTerminar,
                          @Value("${app.seed:classpath:usuarios_iniciales.csv}") String semilla) {
        this.persistencia = persistencia;
        this.passwordEncoder = passwordEncoder;
        this.red = red;
        this.sesion = sesion;
        this.archivo = archivo;
        this.filtros = filtros;
        this.db = db;
        this.pool = pool;
        this.resources = resources;
        this.contexto = contexto;
        this.unaVez = unaVez;
        this.autoInicio = autoInicio;
        this.conConsola = conConsola;
        this.conEscritorio = conEscritorio;
        this.salirAlTerminar = salirAlTerminar;
        this.semilla = semilla;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<UsuarioDTO> semillaCsv = leerSemilla();
        Path directorioUploads = RutasProyecto.resolver(Path.of(red.uploadDir()));
        Path directorioTrabajo = RutasProyecto.resolver(Path.of("work"));
        LOG.info("Imágenes originales y derivadas: {}", directorioUploads);
        Fabrica.SistemaMontado sistema = Fabrica.montar(new Fabrica.Parametros(
                new ConfigRed(red.puerto(), red.pool().core(), red.pool().max(),
                        red.pool().queue(), sesion.timeoutSegundos(),
                        red.pool().esperaSegundos(), red.pool().tramasPorSegundo(),
                        red.tls().habilitado(), red.tls().puerto(),
                        red.tls().almacen(), red.tls().clave()),
                new LimitesDTO(red.limites().maxConexiones(),
                        red.limites().maxConexionesPorUsuario(),
                        sistemaMaxArchivo()),
                filtros.hilos(), filtros.giroGrados(), filtros.brilloFactor(),
                filtros.reduccionFactor(),
                directorioUploads,
                directorioTrabajo,
                persistencia,
                new InterfazUsuariosDisponibles.VerificadorCredenciales() {
                    @Override
                    public boolean verificar(String plano, String hash) {
                        return passwordEncoder.matches(plano, hash);
                    }

                    @Override
                    public String cifrar(String plano) {
                        return passwordEncoder.encode(plano);
                    }
                },
                semillaCsv));
        LOG.info("[FASE-1] motor={} url={} pool={}({}) usuarios={} semilla={}",
                db.nombreActivo(),
                db.motorActivo().url(),
                pool.enabled() ? "Hikari" : "DriverManager",
                pool.maximumPoolSize(),
                sistema.almacenar().contarUsuarios(),
                semillaCsv.size());

        if (unaVez) {
            sistema.apagarPoolFiltros();
            salir();
            return;
        }

        if (autoInicio) {
            try {
                sistema.fachada().iniciarServidor();
            } catch (RuntimeException e) {
                LOG.error("No se pudo iniciar el servidor TCP: {}", e.getMessage());
                codigoSalida = 1;
            }
        }

        Thread escritorio = null;
        if (conEscritorio) {
            escritorio = new Thread(() -> VistaEscritorio.lanzar(sistema.fachada()), "vista-escritorio");
            escritorio.setDaemon(false);
            escritorio.start();
        }
        if (conConsola) {
            new VistaConsola(sistema.fachada()).ejecutar();
        } else if (escritorio != null) {
            escritorio.join();
        }
        sistema.fachada().detenerServidor();
        sistema.apagarPoolFiltros();
        salir();
    }

    private List<UsuarioDTO> leerSemilla() throws Exception {
        Resource recurso = resources.getResource(semilla);
        if (!recurso.exists()) {
            LOG.warn("Semilla no encontrada: {}", semilla);
            return List.of();
        }
        try (InputStream in = recurso.getInputStream()) {
            return SemillaUsuarios.leer(in, passwordEncoder::encode);
        }
    }

    /**
     * Tope efectivo de archivo: el menor entre configuracion_limites (V2: 50 MB)
     * y el techo archivo.max-mb de server.properties (defecto 50 MB).
     */
    private long sistemaMaxArchivo() {
        long techo = archivo == null ? 52_428_800L : archivo.maxBytes();
        try {
            return Math.min(persistencia.leerLimites().maxTamanoArchivo(), techo);
        } catch (Exception e) {
            LOG.warn("Sin limites en BD, maxTamanoArchivo por defecto: {}", e.getMessage());
            return techo;
        }
    }

    private void salir() {
        if (!salirAlTerminar) {
            return;
        }
        System.exit(SpringApplication.exit(contexto, this));
    }

    @Override
    public int getExitCode() {
        return codigoSalida;
    }
}
