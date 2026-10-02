package universidad.mensajeria.server;

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

import java.io.IOException;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Alta SOLO por archivo plano: la via REGISTRO por red esta eliminada y el
 * servidor responde ERROR "tipo no soportado"; los usuarios del CSV si pueden
 * autenticar y un codigo desconocido sigue sin poder entrar.
 */
@SpringBootTest(properties = {"app.inicio=false", "app.consola=false", "app.escritorio=false",
        "app.salir=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegistroSoloCsvE2ETest {

    private static final int TIMEOUT_MS = 5000;
    private static final String PASS_SEED = "Secreta123";

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

    /** Aislamiento total: uploads/work del E2E viven en un temporal (cero residuos). */
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
                new LimitesDTO(red.limites().maxConexiones(),
                        red.limites().maxConexionesPorUsuario(), maxArchivo()),
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
    }

    @AfterAll
    void bajarServidor() {
        fachada.detenerServidor();
        sistema.apagarPoolFiltros();
    }

    @Test
    void registroPorRedRespondeTipoNoSoportadoYNoCreaElUsuario() throws Exception {
        String codigo = "X" + Long.toString(System.currentTimeMillis(), 36).toUpperCase();
        try (ClientePrueba cliente = conectar()) {
            cliente.enviar(Mensaje.builder().tipo(TipoMensaje.REGISTRO)
                    .id("reg-" + UUID.randomUUID()).fechaHora(LocalDateTime.now().toString())
                    .codigo(codigo).contrasena("Nueva123").nombres("No").apellidos("Alta")
                    .programa("Ingenieria de Sistemas").build());
            Mensaje respuesta = cliente.leer();
            assertEquals(TipoMensaje.ERROR, respuesta.tipo());
            assertTrue(respuesta.mensajeError().contains("REGISTRO"),
                    "motivo: " + respuesta.mensajeError());

            // el codigo no existe: LOGIN falla (el usuario solo puede venir del CSV)
            cliente.enviar(Mensaje.builder().tipo(TipoMensaje.LOGIN)
                    .id("login-" + UUID.randomUUID()).fechaHora(LocalDateTime.now().toString())
                    .codigo(codigo).contrasena("Nueva123").build());
            Mensaje login = cliente.leer();
            assertEquals(TipoMensaje.LOGIN_RESPUESTA, login.tipo());
            assertFalse(login.exito(), "el usuario no debe haberse creado");
        }
    }

    @Test
    void usuariosDelCsvSiPuedenAutenticar() throws Exception {
        try (ClientePrueba cliente = conectar()) {
            cliente.enviar(Mensaje.builder().tipo(TipoMensaje.LOGIN)
                    .id("login-" + UUID.randomUUID()).fechaHora(LocalDateTime.now().toString())
                    .codigo("A001234567").contrasena(PASS_SEED).build());
            Mensaje respuesta = cliente.leer();
            assertEquals(TipoMensaje.LOGIN_RESPUESTA, respuesta.tipo());
            assertTrue(respuesta.exito(), "la siembra CSV sigue habilitando LOGIN");
        }
    }

    private long maxArchivo() {
        try {
            return persistencia.leerLimites().maxTamanoArchivo();
        } catch (Exception e) {
            return 52_428_800L;
        }
    }

    private ClientePrueba conectar() throws IOException {
        return new ClientePrueba(puerto);
    }

    private static final class ClientePrueba implements AutoCloseable {

        private final Socket socket;

        private ClientePrueba(int puerto) throws IOException {
            socket = new Socket("127.0.0.1", puerto);
            socket.setSoTimeout(TIMEOUT_MS);
            socket.setTcpNoDelay(true);
        }

        private void enviar(Mensaje mensaje) throws IOException {
            TramaCodec.escribir(socket.getOutputStream(), mensaje);
        }

        private Mensaje leer() throws IOException {
            return TramaCodec.leer(socket.getInputStream());
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
