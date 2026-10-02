package universidad.mensajeria.server.inicio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import universidad.mensajeria.colas.InterfazGestorMensajes;
import universidad.mensajeria.colas.ProveedorGestorMensajes;
import universidad.mensajeria.common.interno.EventoEtapa;
import universidad.mensajeria.common.interno.LimitesDTO;
import universidad.mensajeria.common.interno.UsuarioDTO;
import universidad.mensajeria.protocolo.ConfigRed;
import universidad.mensajeria.protocolo.InterfazProtocoloComunicacion;
import universidad.mensajeria.protocolo.ProveedorProtocoloComunicacion;

import universidad.mensajeria.clienteservidor.InterfazClienteServidor;
import universidad.mensajeria.clienteservidor.ProveedorClienteServidor;

import universidad.mensajeria.servicios.InterfazServiciosDisponibles;
import universidad.mensajeria.servicios.ProveedorServiciosDisponibles;

import universidad.mensajeria.usuarios.InterfazUsuariosDisponibles;
import universidad.mensajeria.usuarios.ProveedorUsuariosDisponibles;

import universidad.mensajeria.server.fachadadeservicios.FachadaDeServicios;
import universidad.mensajeria.server.fachadadeservicios.casosdeuso.AutenticarCaso;
import universidad.mensajeria.server.fachadadeservicios.casosdeuso.Casos;
import universidad.mensajeria.server.fachadadeservicios.casosdeuso.CerrarAdminCaso;
import universidad.mensajeria.server.fachadadeservicios.casosdeuso.CerrarCaso;
import universidad.mensajeria.server.fachadadeservicios.casosdeuso.DescargaCaso;
import universidad.mensajeria.server.fachadadeservicios.casosdeuso.EncolarCaso;
import universidad.mensajeria.server.fachadadeservicios.casosdeuso.ExpulsarCaso;
import universidad.mensajeria.server.fachadadeservicios.casosdeuso.FragmentoArchivoCaso;
import universidad.mensajeria.server.fachadadeservicios.casosdeuso.HistorialCaso;
import universidad.mensajeria.server.fachadadeservicios.casosdeuso.ListarCaso;
import universidad.mensajeria.server.fachadadeservicios.casosdeuso.ResponderCaso;

import universidad.mensajeria.server.almacenarinformacion.InterfazAlmacenarInformacion;
import universidad.mensajeria.server.almacenarinformacion.PersistenciaPort;
import universidad.mensajeria.server.almacenarinformacion.AlmacenarInformacion;

import universidad.mensajeria.server.conexionesclientes.ConexionesClientes;
import universidad.mensajeria.server.conexionesclientes.InterfazConexionesClientes;

import universidad.mensajeria.server.logdeeventos.InterfazLogDeEventos;
import universidad.mensajeria.server.logdeeventos.LogDeEventos;

import universidad.mensajeria.server.mensajes.InterfazMensajes;
import universidad.mensajeria.server.mensajes.Mensajes;

import universidad.mensajeria.common.interno.TipoAccion;

import universidad.mensajeria.utilerias.TuberiaFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Raiz de composicion del dominio (PLAN2 §2.4): descubre los 5 comp-* y cablea
 * nucleo, casos y Fachada. Spring/InicioServidor compone aparte la infraestructura.
 */
public final class Fabrica {

    private static final Logger LOG = LoggerFactory.getLogger(Fabrica.class);

    private Fabrica() {
    }

    /** Todo lo que Fabrica necesita y Spring NO provee como comp-*. */
    public record Parametros(
            ConfigRed red,
            LimitesDTO limites,
            int filtrosHilos,
            int giroGrados,
            double brilloFactor,
            double reduccionFactor,
            Path uploadDir,
            Path trabajoBase,
            PersistenciaPort persistencia,
            InterfazUsuariosDisponibles.VerificadorCredenciales verificador,
            List<UsuarioDTO> semillaCsv
    ) {
    }

    /** Sistema montado y listo (las vistas lo consumen via Fachada). */
    public record SistemaMontado(
            FachadaDeServicios fachada,
            InterfazProtocoloComunicacion protocolo,
            InterfazConexionesClientes conexiones,
            InterfazLogDeEventos eventos,
            InterfazAlmacenarInformacion almacenar,
            InterfazUsuariosDisponibles usuarios,
            Executor poolFiltros
    ) {
        public void apagarPoolFiltros() {
            if (poolFiltros instanceof ThreadPoolExecutor pool) {
                pool.shutdownNow();
            }
        }
    }

    public static SistemaMontado montar(Parametros p) {
        InterfazProtocoloComunicacion protocolo =
                unico(ServiceLoader.load(ProveedorProtocoloComunicacion.class)).crear(p.red());
        ProveedorClienteServidor proveedorDespachador =
                unico(ServiceLoader.load(ProveedorClienteServidor.class));
        InterfazServiciosDisponibles servicios =
                unico(ServiceLoader.load(ProveedorServiciosDisponibles.class)).crear();
        InterfazUsuariosDisponibles usuarios =
                unico(ServiceLoader.load(ProveedorUsuariosDisponibles.class)).crear();
        InterfazGestorMensajes colas =
                unico(ServiceLoader.load(ProveedorGestorMensajes.class)).crear();

        InterfazAlmacenarInformacion almacenar =
                new AlmacenarInformacion(p.persistencia(), p.trabajoBase(), p.uploadDir());
        InterfazLogDeEventos eventos = new LogDeEventos(almacenar);
        InterfazConexionesClientes conexiones = new ConexionesClientes();

        AtomicInteger hilos = new AtomicInteger();
        ThreadPoolExecutor poolFiltros = new ThreadPoolExecutor(
                p.filtrosHilos(), p.filtrosHilos(), 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(16, p.filtrosHilos() * 4)),
                r -> {
                    Thread t = new Thread(r, "filtros-" + hilos.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());

        TuberiaFactory tuberias = new TuberiaFactory(p.giroGrados(), p.brilloFactor(),
                p.reduccionFactor());
        InterfazMensajes mensajes = new Mensajes(almacenar, tuberias, poolFiltros,
                etapa -> eventos.registrar(TipoAccion.ETAPA_FILTRADO,
                        "Etapa %s: %d ms [%s] -> %s".formatted(etapa.etapa(), etapa.ms(),
                                etapa.hilo(), etapa.rutaSalida()),
                        null, null, null));

        ResponderCaso responder = new ResponderCaso(protocolo);
        EncolarCaso encolar = new EncolarCaso(colas);
        Casos casos = new Casos(
                new AutenticarCaso(protocolo, servicios, usuarios, conexiones, almacenar,
                        eventos, p.verificador(), p.limites(), responder),
                new CerrarCaso(protocolo, eventos),
                new ExpulsarCaso(protocolo, conexiones, eventos, responder),
                new CerrarAdminCaso(protocolo, conexiones, eventos),
                encolar,
                new FragmentoArchivoCaso(servicios, p.limites(), p.trabajoBase(), encolar,
                        responder, eventos),
                new HistorialCaso(almacenar, protocolo, responder),
                new DescargaCaso(almacenar, protocolo, responder, p.uploadDir()),
                new ListarCaso(protocolo, conexiones, usuarios),
                responder);

        // Rompe el ciclo Fachada<->Despachador: la Fachada recibe un Supplier
        // que Fabrica resuelve justo despues con el despachador real.
        java.util.concurrent.atomic.AtomicReference<InterfazClienteServidor> despachadorRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        FachadaDeServicios fachada = new FachadaDeServicios(protocolo, despachadorRef::get,
                servicios, usuarios, colas, mensajes, eventos, conexiones, almacenar,
                p.limites(), casos);

        // ...y el despachador real se cablea con la Fachada como salida (rompe
        // el ciclo Fachada<->Despachador via SPI, sin que servidor-app vea impl).
        InterfazClienteServidor despachador = proveedorDespachador.crear(fachada);
        despachadorRef.set(despachador);

        despachador.registrarSalida(fachada);
        protocolo.registrarReceptor(fachada);
        colas.registrarConsumidor(fachada);

        sembrar(usuarios, almacenar, eventos, p.semillaCsv());

        LOG.info("Sistema montado: 5 comp-* por ServiceLoader + nucleo + fachada");
        return new SistemaMontado(fachada, protocolo, conexiones, eventos, almacenar, usuarios,
                poolFiltros);
    }

    private static void sembrar(InterfazUsuariosDisponibles usuarios,
                                InterfazAlmacenarInformacion almacenar,
                                InterfazLogDeEventos eventos,
                                List<UsuarioDTO> semillaCsv) {
        // CSV es la fuente de altas y cambios; las bajas se desactivan en BD.
        List<UsuarioDTO> nuevos = new ArrayList<>();
        for (UsuarioDTO semilla : semillaCsv) {
            if (!almacenar.existeUsuarioPorCodigo(semilla.codigo())) {
                nuevos.add(semilla);
            }
        }
        almacenar.reconciliarUsuariosDesdeCsv(semillaCsv);
        for (UsuarioDTO usuario : nuevos) {
            eventos.registrar(TipoAccion.USUARIO_REGISTRADO,
                    "Usuario sembrado: " + usuario.codigo(), usuario.codigo(), null, null);
        }
        usuarios.sembrar(almacenar.listarUsuarios());
        LOG.info("Siembra: {} usuarios en memoria, {} nuevos desde CSV",
                usuarios.listar().size(), nuevos);
    }

    private static <T> T unico(ServiceLoader<T> loader) {
        List<T> encontrados = new ArrayList<>();
        loader.forEach(encontrados::add);
        if (encontrados.size() != 1) {
            throw new IllegalStateException(
                    "Se esperaba exactamente 1 proveedor, hay " + encontrados.size());
        }
        return encontrados.get(0);
    }
}
