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
import universidad.mensajeria.common.interno.UsuarioYaExisteException;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.common.tipos.TipoMensaje;
import universidad.mensajeria.protocolo.ConfigRed;
import universidad.mensajeria.server.inicio.Fabrica;
import universidad.mensajeria.server.inicio.SemillaUsuarios;
import universidad.mensajeria.server.almacenarinformacion.PersistenciaPort;
import universidad.mensajeria.server.transversal.config.FiltrosProperties;
import universidad.mensajeria.server.transversal.config.RedProperties;
import universidad.mensajeria.server.transversal.config.SesionProperties;
import universidad.mensajeria.server.transversal.fachada.Fachada;
import universidad.mensajeria.usuarios.InterfazUsuariosDisponibles;

import java.io.IOException;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FASE 1 — criterio de verificación "Login/listar funcionando":
 * cliente TCP real (trama 4B + JSON de PROTOCOLO.md) contra el servidor con
 * Commands + pool acotado + mapa de conexiones inyectado.
 * Requiere MySQL (docker compose up mysql) — el esquema lo define Flyway.
 */
@SpringBootTest(properties = {"app.inicio=false", "app.consola=false", "app.escritorio=false",
        "app.salir=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoginListarE2ETest {

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
    void loginConCredencialesValidasRespondeExito() throws Exception {
        try (ClientePrueba cliente = conectar()) {
            cliente.enviar(Mensaje.builder().tipo(TipoMensaje.LOGIN)
                    .id("login-1").fechaHora(LocalDateTime.now().toString())
                    .codigo("A001234567").contrasena(PASS_SEED).build());
            Mensaje respuesta = cliente.leer();

            assertEquals(TipoMensaje.LOGIN_RESPUESTA, respuesta.tipo());
            assertTrue(respuesta.exito(), "esperaba exito=true");
            assertEquals("login-1", respuesta.id());
            assertTrue(respuesta.ipRemitente() != null && !respuesta.ipRemitente().isBlank());
            esperar(() -> fachada.usuariosConectados().contains("A001234567"));
        }
    }

    @Test
    void loginConPasswordIncorrectaRespondeFallo() throws Exception {
        try (ClientePrueba cliente = conectar()) {
            Mensaje respuesta = login(cliente, "C003456789", "password-mala");

            assertEquals(TipoMensaje.LOGIN_RESPUESTA, respuesta.tipo());
            assertFalse(respuesta.exito());
            assertTrue(respuesta.mensajeError() != null && !respuesta.mensajeError().isBlank());
        }
    }

    @Test
    void listarConectadosReflejaSesionesYMultiConexion() throws Exception {
        try (ClientePrueba primera = conectar();
             ClientePrueba segunda = conectar()) {

            assertTrue(login(primera, "D004567890", PASS_SEED).exito());
            assertTrue(login(segunda, "D004567890", PASS_SEED).exito(),
                    "el mismo codigo debe poder conectarse varias veces (req. #10)");

            segunda.enviar(Mensaje.builder().tipo(TipoMensaje.LISTAR_CONECTADOS)
                    .id("listar-1").fechaHora(LocalDateTime.now().toString()).build());
            Mensaje respuesta = segunda.leerSalvoSync();

            assertEquals(TipoMensaje.LISTAR_CONECTADOS_RESPUESTA, respuesta.tipo());
            assertEquals("listar-1", respuesta.id());
            List<String> conectados = respuesta.usuariosConectados();
            assertTrue(conectados.contains("D004567890"));
            Set<String> distintos = new HashSet<>(conectados);
            assertEquals(conectados.size(), distintos.size(), "el listado no debe repetir codigos");

            long sesionesDelCodigo = fachada.usuariosConectados().stream()
                    .filter(c -> c.equals("D004567890")).count();
            assertEquals(1, sesionesDelCodigo, "un codigo = una entrada en el listado");
            assertTrue(fachada.estadoServidor().sesionesActivas() >= 2);
        }
    }

    @Test
    void limiteDeConexionesPorUsuarioRechazaLaCuartaSesion() throws Exception {
        List<ClientePrueba> sesiones = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                ClientePrueba cliente = conectar();
                sesiones.add(cliente);
                assertTrue(login(cliente, "B002345678", PASS_SEED).exito(),
                        "la sesion " + (i + 1) + " deberia entrar (max por usuario = 3)");
            }

            try (ClientePrueba cuarta = conectar()) {
                cuarta.enviar(Mensaje.builder().tipo(TipoMensaje.LOGIN)
                        .id("limite-1").fechaHora(LocalDateTime.now().toString())
                        .codigo("B002345678").contrasena(PASS_SEED).build());

                Mensaje rechazo = cuarta.leer();
                assertEquals(TipoMensaje.LOGIN_RESPUESTA, rechazo.tipo());
                assertFalse(rechazo.exito());

                Mensaje aviso = cuarta.leer();
                assertEquals(TipoMensaje.CLOSE_NOTICE, aviso.tipo());
            }
        } finally {
            sesiones.forEach(ClientePrueba::close);
        }
    }

    @Test
    void altaDirectaCreaUsuarioQueLuegoPuedeLoguear() throws Exception {
        String codigo = "E" + Long.toString(System.currentTimeMillis(), 36).toUpperCase();
        darDeAlta(codigo, "Nueva123");

        try (ClientePrueba cliente = conectar()) {
            assertTrue(login(cliente, codigo, "Nueva123").exito());

            assertThrows(UsuarioYaExisteException.class, () -> darDeAlta(codigo, "Nueva123"));
        }
    }

    @Test
    void logoutCierraSoloLaSesionSolicitante() throws Exception {
        try (ClientePrueba cliente = conectar()) {
            assertTrue(login(cliente, "C003456789", PASS_SEED).exito());

            cliente.enviar(Mensaje.builder().tipo(TipoMensaje.LOGOUT)
                    .id("logout-1").fechaHora(LocalDateTime.now().toString()).build());

            Mensaje aviso = cliente.leerSalvoSync();
            assertEquals(TipoMensaje.CLOSE_NOTICE, aviso.tipo());
            assertThrows(IOException.class, () -> cliente.leer(),
                    "tras CLOSE_NOTICE el socket debe quedar cerrado");

            esperar(() -> !fachada.usuariosConectados().contains("C003456789"));
        }
    }

    @Test
    void logoutDeUnaSesionMantieneOnlineLaOtraSesionDelMismoUsuario() throws Exception {
        try (ClientePrueba primera = conectar(); ClientePrueba segunda = conectar()) {
            assertTrue(login(primera, "A001234567", PASS_SEED).exito());
            assertTrue(login(segunda, "A001234567", PASS_SEED).exito());

            primera.enviar(Mensaje.builder().tipo(TipoMensaje.LOGOUT)
                    .id("logout-primera").fechaHora(LocalDateTime.now().toString()).build());
            assertEquals(TipoMensaje.CLOSE_NOTICE, primera.leerSalvoSync().tipo());

            esperar(() -> fachada.usuariosConectados().contains("A001234567"));
            segunda.enviar(Mensaje.builder().tipo(TipoMensaje.LISTAR_CONECTADOS)
                    .id("listar-segunda").fechaHora(LocalDateTime.now().toString()).build());
            Mensaje respuesta = segunda.leerSalvoSync();
            assertEquals(TipoMensaje.LISTAR_CONECTADOS_RESPUESTA, respuesta.tipo());
            assertTrue(respuesta.usuariosConectados().contains("A001234567"));
        }
    }

    @Test
    void poolAcotadoYLimitesSegunConfig() {
        assertTrue(red.pool().max() > 0 && red.pool().max() < 1000,
                "pool acotado por server.properties, jamas un cachedThreadPool ilimitado");

        assertTrue(fachada.estadoServidor().activo());
        assertEquals(red.puerto(), fachada.estadoServidor().puerto());
        assertEquals(red.limites().maxConexiones(),
                fachada.estadoServidor().maxConexiones());
    }

    @Test
    void historialSinLoginRespondeErrorSinTumbarElServidor() throws Exception {
        // FASE 6: HISTORIAL_REQ ya tiene handler; sin LOGIN responde ERROR
        // de autenticacion (antes: "tipo no soportado").
        try (ClientePrueba cliente = conectar()) {
            cliente.enviar(Mensaje.builder().tipo(TipoMensaje.HISTORIAL_REQ)
                    .id("x").fechaHora(LocalDateTime.now().toString()).build());

            Mensaje error = cliente.leer();
            assertEquals(TipoMensaje.ERROR, error.tipo());
            assertTrue(error.mensajeError().contains("LOGIN"));

            assertTrue(login(cliente, "A001234567", PASS_SEED).exito(),
                    "la sesion debe seguir viva tras un comando rechazado");
        }
    }

    // ------------------------------------------------------------------ util

    private ClientePrueba conectar() throws IOException {
        return new ClientePrueba(puerto);
    }

    private Mensaje login(ClientePrueba cliente, String codigo, String contrasena) throws IOException {
        cliente.enviar(Mensaje.builder()
                .tipo(TipoMensaje.LOGIN)
                .id("login-" + UUID.randomUUID())
                .fechaHora(LocalDateTime.now().toString())
                .codigo(codigo)
                .contrasena(contrasena)
                .build());
        while (true) {
            Mensaje respuesta = cliente.leer();
            if (respuesta.tipo() == TipoMensaje.LOGIN_RESPUESTA) {
                return respuesta;
            }
        }
    }

    /**
     * Alta directa en memoria + persistencia (la via REGISTRO por red esta
     * eliminada): replica el alta que antes hacia el caso de uso.
     */
    private void darDeAlta(String codigo, String contrasena) {
        UsuarioDTO creado = sistema.usuarios().registrar(codigo,
                passwordEncoder.encode(contrasena), "Prueba", "E2E", "Ingenieria de Sistemas");
        if (!sistema.almacenar().existeUsuarioPorCodigo(creado.codigo())) {
            sistema.almacenar().registrarUsuarioNuevo(creado.codigo(), creado.hashContrasena(),
                    creado.nombres(), creado.apellidos(), creado.programa());
        }
    }

    private static void esperar(BooleanSupplier condicion) throws InterruptedException {
        long limite = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < limite && !condicion.getAsBoolean()) {
            Thread.sleep(50);
        }
        assertTrue(condicion.getAsBoolean(), "la condicion no se cumplio en " + TIMEOUT_MS + " ms");
    }

    /** Cliente TCP minimo sobre el wire protocol (mismo codigo que usara el cliente Java). */
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

        /**
         * Lee saltando tramas async (SYNC, PRESENCIA, ENTREGADO, LEIDO).
         */
        private Mensaje leerSalvoSync() throws IOException {
            while (true) {
                Mensaje trama = leer();
                if (trama.tipo() != TipoMensaje.SYNC_LOGIN
                        && trama.tipo() != TipoMensaje.PRESENCIA
                        && trama.tipo() != TipoMensaje.MENSAJE_ENTREGADO
                        && trama.tipo() != TipoMensaje.MENSAJE_LEIDO) {
                    return trama;
                }
            }
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignorada) {
                // ya cerrado
            }
        }
    }
}
