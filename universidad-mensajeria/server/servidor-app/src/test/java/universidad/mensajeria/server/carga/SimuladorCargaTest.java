package universidad.mensajeria.server.carga;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.crypto.password.PasswordEncoder;
import universidad.mensajeria.common.codec.TramaCodec;
import universidad.mensajeria.common.interno.EstadoServidor;
import universidad.mensajeria.common.interno.LimitesDTO;
import universidad.mensajeria.common.interno.UsuarioDTO;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.common.tipos.TipoMensaje;
import universidad.mensajeria.protocolo.ConfigRed;
import universidad.mensajeria.server.almacenarinformacion.PersistenciaPort;
import universidad.mensajeria.server.inicio.Fabrica;
import universidad.mensajeria.server.inicio.SemillaUsuarios;
import universidad.mensajeria.server.transversal.config.FiltrosProperties;
import universidad.mensajeria.server.transversal.config.RedProperties;
import universidad.mensajeria.server.transversal.config.SesionProperties;
import universidad.mensajeria.server.transversal.fachada.Fachada;
import universidad.mensajeria.usuarios.InterfazUsuariosDisponibles;

import java.net.Socket;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FASE 11.5: 50 clientes concurrentes con rafaga de 100 imagenes... de textos:
 * mide tramas/s, saturacion del pool y recuperacion. Requiere MySQL.
 */
@SpringBootTest(properties = {"app.inicio=false", "app.consola=false", "app.escritorio=false",
        "app.salir=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimuladorCargaTest {

    private static final int CLIENTES = 50;
    private static final int MENSAJES_POR_CLIENTE = 2;
    private static final int TIMEOUT_MS = 15000;

    @Autowired
    private PersistenciaPort persistencia;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private RedProperties red;
    @Autowired
    private SesionProperties sesion;
    @Autowired
    private FiltrosProperties filtros;
    @Autowired
    private ResourceLoader resources;

    private Fabrica.SistemaMontado sistema;
    private Fachada fachada;
    private int puerto;
    private String prefijo;

    @TempDir
    static java.nio.file.Path baseTemporal;

    @BeforeAll
    void subirServidor() throws Exception {
        List<UsuarioDTO> semilla = SemillaUsuarios.leer(
                resources.getResource("classpath:usuarios_iniciales.csv").getInputStream(),
                passwordEncoder::encode);
        java.nio.file.Path dirUploads =
                java.nio.file.Files.createDirectories(baseTemporal.resolve("uploads"));
        java.nio.file.Path dirWork =
                java.nio.file.Files.createDirectories(baseTemporal.resolve("work"));
        sistema = Fabrica.montar(new Fabrica.Parametros(
                new ConfigRed(red.puerto(), red.pool().core(), red.pool().max(),
                        red.pool().queue(), sesion.timeoutSegundos()),
                new LimitesDTO(0, 0, maxArchivo()),
                filtros.hilos(), filtros.giroGrados(), filtros.brilloFactor(),
                filtros.reduccionFactor(),
                dirUploads,
                dirWork,
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
                semilla));
        fachada = sistema.fachada();
        fachada.iniciarServidor();
        puerto = red.puerto();
        prefijo = "L" + Long.toString(System.currentTimeMillis(), 36).toUpperCase();
        for (int i = 0; i < CLIENTES; i++) {
            String codigo = prefijo + i;
            UsuarioDTO creado = sistema.usuarios().registrar(codigo,
                    passwordEncoder.encode("Secreta123"), "Carga", "Simulada", "Sistemas");
            if (!sistema.almacenar().existeUsuarioPorCodigo(creado.codigo())) {
                sistema.almacenar().registrarUsuarioNuevo(creado.codigo(),
                        creado.hashContrasena(), creado.nombres(), creado.apellidos(),
                        creado.programa());
            }
        }
    }

    private long maxArchivo() {
        try {
            return persistencia.leerLimites().maxTamanoArchivo();
        } catch (Exception e) {
            return 52_428_800L;
        }
    }

    @AfterAll
    void bajarServidor() {
        fachada.detenerServidor();
        sistema.apagarPoolFiltros();
    }

    @Test
    void cincuentaClientesConcurrentesSinSaturarElPool() throws Exception {
        EstadoServidor antes = fachada.estadoServidor();
        AtomicLong acks = new AtomicLong();
        ExecutorService clientes = Executors.newFixedThreadPool(CLIENTES);
        try {
            List<Callable<Void>> tareas = new ArrayList<>();
            for (int i = 0; i < CLIENTES; i++) {
                final String codigo = prefijo + i;
                tareas.add(() -> {
                    try (Socket socket = new Socket("127.0.0.1", puerto)) {
                        socket.setSoTimeout(TIMEOUT_MS);
                        socket.setTcpNoDelay(true);
                            enviar(socket, Mensaje.builder().tipo(TipoMensaje.LOGIN)
                                    .id("login-" + UUID.randomUUID())
                                    .fechaHora(LocalDateTime.now().toString())
                                    .codigo(codigo).contrasena("Secreta123").build());
                            assertEquals(TipoMensaje.LOGIN_RESPUESTA, leerSalvoSync(socket).tipo());
                            for (int m = 0; m < MENSAJES_POR_CLIENTE; m++) {
                                enviar(socket, Mensaje.builder().tipo(TipoMensaje.MENSAJE_TEXTO)
                                        .id("carga-" + UUID.randomUUID())
                                        .fechaHora(LocalDateTime.now().toString())
                                        .remitente(codigo).destinatario(prefijo + "0")
                                        .contenido("carga " + m + " de " + codigo).build());
                                assertEquals(TipoMensaje.ACK, leerHastaAck(socket).tipo());
                                acks.incrementAndGet();
                            }
                    }
                    return null;
                });
            }
            long inicio = System.nanoTime();
            for (Future<Void> f : clientes.invokeAll(tareas, 5, TimeUnit.MINUTES)) {
                f.get();
            }
            double segundos = (System.nanoTime() - inicio) / 1_000_000_000.0;
            EstadoServidor despues = fachada.estadoServidor();
            long total = (long) CLIENTES * MENSAJES_POR_CLIENTE;
            System.out.printf("[carga] %d ACKs en %.1fs = %.1f tramas/s; pool %d/%d ocupados; "
                            + "procesados +%d; en cola %d%n",
                    acks.get(), segundos, acks.get() / Math.max(segundos, 0.001),
                    despues.trabajadoresOcupados(), despues.trabajadoresTotal(),
                    despues.mensajesProcesados() - antes.mensajesProcesados(),
                    despues.mensajesEnCola());
            assertEquals(total, acks.get());
            // La ultima desconexion se poda en fondo: espera acotada a la liberacion.
            EstadoServidor liberado = despues;
            long tope = System.currentTimeMillis() + 15000;
            while (liberado.trabajadoresDisponibles() != liberado.trabajadoresTotal()
                    && System.currentTimeMillis() < tope) {
                Thread.sleep(100);
                liberado = fachada.estadoServidor();
            }
            assertEquals(liberado.trabajadoresTotal(), liberado.trabajadoresDisponibles(),
                    "el pool debe liberarse tras la rafaga");
        } finally {
            clientes.shutdownNow();
        }
    }

    private static void enviar(Socket socket, Mensaje mensaje) throws Exception {
        TramaCodec.escribir(socket.getOutputStream(), mensaje);
    }

    private static Mensaje leerSalvoSync(Socket socket) throws Exception {
        while (true) {
            Mensaje trama = TramaCodec.leer(socket.getInputStream());
            if (trama.tipo() != TipoMensaje.SYNC_LOGIN
                    && trama.tipo() != TipoMensaje.PRESENCIA
                    && trama.tipo() != TipoMensaje.MENSAJE_ENTREGADO
                    && trama.tipo() != TipoMensaje.MENSAJE_LEIDO) {
                return trama;
            }
        }
    }

    /**
     * Las entregas dirigidas a este mismo socket (C0 recibe 100) se saltan:
     * solo vale el ACK/ERROR correlacionado.
     */
    private static Mensaje leerHastaAck(Socket socket) throws Exception {
        while (true) {
            Mensaje trama = TramaCodec.leer(socket.getInputStream());
            if (trama.tipo() == TipoMensaje.ACK || trama.tipo() == TipoMensaje.ERROR) {
                return trama;
            }
        }
    }
}
