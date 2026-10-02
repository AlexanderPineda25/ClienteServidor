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
import universidad.mensajeria.common.interno.InformeDTO;
import universidad.mensajeria.common.interno.InformeFiltroDTO;
import universidad.mensajeria.common.interno.LimitesDTO;
import universidad.mensajeria.common.interno.TipoAccion;
import universidad.mensajeria.common.interno.UsuarioDTO;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.common.tipos.TipoMensaje;
import universidad.mensajeria.protocolo.ConfigRed;
import universidad.mensajeria.server.almacenarinformacion.PersistenciaPort;
import universidad.mensajeria.server.inicio.Fabrica;
import universidad.mensajeria.server.inicio.SemillaUsuarios;
import universidad.mensajeria.server.persistencia.repositorio.MensajeRepository;
import universidad.mensajeria.server.persistencia.repositorio.RegistroAccionRepository;
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
 * FASE 9 — §8: los 4 informes contra la BD real (misma fixture que los otros
 * E2E). Los conteos se contrastan con los repositorios en el MISMO instante
 * (DB compartida entre clases: comparaciones >= y por usuario, nunca totales
 * absolutos de otras fixtures). Requiere MySQL (docker compose up mysql).
 */
@SpringBootTest(properties = {"app.inicio=false", "app.consola=false", "app.escritorio=false",
        "app.salir=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InformesE2ETest {

    private static final int TIMEOUT_MS = 8000;
    private static final String PASS_SEED = "Secreta123";
    private static final LocalDateTime MIN = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final LocalDateTime MAX = LocalDateTime.of(9999, 12, 31, 23, 59, 59);

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
    @Autowired
    private MensajeRepository mensajes;
    @Autowired
    private RegistroAccionRepository acciones;

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
    void losCuatroInformesReflejanElSistemaReal() throws Exception {
        String emisor = registrarUsuarioNuevo();
        String receptor = registrarUsuarioNuevo();
        try (ClientePrueba a = conectar(); ClientePrueba b = conectar()) {
            assertTrue(login(a, emisor).exito());
            assertTrue(login(b, receptor).exito());
            a.enviar(Mensaje.builder().tipo(TipoMensaje.MENSAJE_TEXTO)
                    .id("inf-1").fechaHora(LocalDateTime.now().toString())
                    .destinatario(receptor).contenido("hola informes").build());
            assertEquals(TipoMensaje.ACK, a.leerSalvoSync().tipo(), "ACK tras persistir");

            // ---- Informe 1: directorio (RF-S23) ----
            InformeDTO usuarios = fachada.informeUsuarios(InformeFiltroDTO.sinFiltro());
            assertEquals(List.of("Codigo", "Nombres", "Apellidos", "Programa",
                    "Registro", "Ultima conexion", "Conectado"), usuarios.columnas());
            assertTrue(usuarios.filas().size() >= fachada.usuariosRegistrados().size());
            List<String> filaEmisor = usuarios.filas().stream()
                    .filter(f -> emisor.equals(f.get(0))).findFirst().orElseThrow();
            assertEquals("Si", filaEmisor.get(6), "A tiene sesion viva");

            InformeDTO soloEmisor = fachada.informeUsuarios(
                    new InformeFiltroDTO(null, null, emisor));
            assertEquals(1, soloEmisor.filas().size(), "filtro por codigo");
            assertEquals(emisor, soloEmisor.filas().get(0).get(0));

            // ---- Informe 2: frecuencia de acceso (RF-S24) ----
            InformeDTO conexiones = fachada.informeConexiones(InformeFiltroDTO.sinFiltro());
            assertEquals(List.of("Usuario [código]", "Veces conectado", "Sesiones vivas",
                    "Ultima conexion"), conexiones.columnas());
            List<String> filaConexiones = conexiones.filas().stream()
                    .filter(f -> f.get(0).endsWith("[" + emisor + "]"))
                    .findFirst().orElseThrow();
            long veces = Long.parseLong(filaConexiones.get(1));
            assertTrue(veces >= 1, "el login del emisor debe contar");
            long loginsEmisor = acciones.buscarParaInforme(MIN, MAX, emisor).stream()
                    .filter(r -> r.getTipo() == TipoAccion.LOGIN).count();
            assertEquals(loginsEmisor, veces,
                    "mensajes y otras acciones no incrementan el contador de logins");
            assertTrue(Integer.parseInt(filaConexiones.get(2)) >= 1,
                    "sesion viva del emisor");

            // cruzado: el GROUP BY debe sumar exactamente los LOGIN de la tabla
            long loginsTabla = acciones.buscarParaInforme(MIN, MAX, "").stream()
                    .filter(r -> r.getTipo() == TipoAccion.LOGIN).count();
            long sumaInforme = conexiones.filas().stream()
                    .mapToLong(f -> Long.parseLong(f.get(1))).sum();
            assertEquals(loginsTabla, sumaInforme, "COUNT SQL == filas LOGIN en JPQL");

            // ---- Informe 3: historico de mensajes (RF-S25) ----
            InformeDTO historico = fachada.informeMensajes(InformeFiltroDTO.sinFiltro());
            assertEquals(List.of("Id", "Fecha", "Remitente [código]", "Destinatario [código]", "Tipo",
                    "Detalle", "Hash SHA-256"), historico.columnas());
            assertEquals(mensajes.count(), historico.filas().size(),
                    "1 fila por mensaje persistido (mismo instante)");
            assertTrue(historico.filas().stream().anyMatch(f ->
                    f.get(2).endsWith("[" + emisor + "]")
                            && f.get(3).endsWith("[" + receptor + "]")
                            && "TEXTO".equals(f.get(4))
                            && "13 car. · 2 pal.".equals(f.get(5))),
                    "nuestro mensaje con metricas de texto");

            InformeDTO soloEmisorMensajes = fachada.informeMensajes(
                    new InformeFiltroDTO(null, null, emisor));
            assertFalse(soloEmisorMensajes.filas().isEmpty());
            assertTrue(soloEmisorMensajes.filas().stream().allMatch(f ->
                    f.get(2).endsWith("[" + emisor + "]")
                            || f.get(3).endsWith("[" + emisor + "]")),
                    "filtro: emisor o destinatario en cada fila");

            // ---- Informe 4: bitacora de auditoria (RF-S26) ----
            InformeDTO auditoria = fachada.informeAuditoria(InformeFiltroDTO.sinFiltro());
            assertEquals(List.of("Id", "Fecha", "Tipo", "Usuario [código]", "IP", "Descripcion"),
                    auditoria.columnas());
            assertEquals(acciones.count(), auditoria.filas().size(),
                    "1 fila por accion registrada (mismo instante)");
            assertTrue(auditoria.filas().stream().anyMatch(f ->
                    "LOGIN".equals(f.get(2)) && f.get(3).endsWith("[" + emisor + "]")),
                    "el LOGIN del emisor queda en la bitacora");

            InformeDTO soloEmisorAuditoria = fachada.informeAuditoria(
                    new InformeFiltroDTO(null, null, emisor));
            assertFalse(soloEmisorAuditoria.filas().isEmpty());
            assertTrue(soloEmisorAuditoria.filas().stream().allMatch(f ->
                    f.get(3).endsWith("[" + emisor + "]")), "filtro: solo acciones del emisor");
        }
    }

    @Test
    void filtroPorFechaAislaElRangoSolicitado() {
        // sin registros en 1970: el rango acotado no debe traer nada
        InformeDTO vacio = fachada.informeAuditoria(
                new InformeFiltroDTO("1970-01-01", "1970-01-02", null));
        assertTrue(vacio.filas().isEmpty(), "rango historico vacio");

        // el dia de hoy debe traer al menos las acciones de esta fixture
        String hoy = java.time.LocalDate.now().toString();
        InformeDTO hoyInforme = fachada.informeAuditoria(
                new InformeFiltroDTO(hoy, hoy, null));
        assertFalse(hoyInforme.filas().isEmpty(), "hoy hay logins de los E2E");
    }

    // ------------------------------------------------------------------ util

    private ClientePrueba conectar() throws IOException {
        return new ClientePrueba(puerto);
    }

    /**
     * Alta directa en memoria + persistencia (la via REGISTRO por red esta
     * eliminada): replica el alta que antes hacia el caso de uso.
     */
    private String registrarUsuarioNuevo() {
        String codigo = "I" + Long.toString(System.currentTimeMillis(), 36).toUpperCase()
                + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        UsuarioDTO creado = sistema.usuarios().registrar(codigo,
                passwordEncoder.encode("Nueva123"), "Fase9", "Informes", "Ingenieria de Sistemas");
        if (!sistema.almacenar().existeUsuarioPorCodigo(creado.codigo())) {
            sistema.almacenar().registrarUsuarioNuevo(creado.codigo(), creado.hashContrasena(),
                    creado.nombres(), creado.apellidos(), creado.programa());
        }
        return codigo;
    }

    private Mensaje login(ClientePrueba cliente, String codigo) throws IOException {
        cliente.enviar(Mensaje.builder()
                .tipo(TipoMensaje.LOGIN)
                .id("login-" + UUID.randomUUID())
                .fechaHora(LocalDateTime.now().toString())
                .codigo(codigo)
                .contrasena("Nueva123")
                .build());
        while (true) {
            Mensaje respuesta = cliente.leer();
            if (respuesta.tipo() == TipoMensaje.LOGIN_RESPUESTA) {
                return respuesta;
            }
        }
    }

    /** Cliente TCP minimo sobre el wire protocol (patron de MensajeriaE2ETest). */
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
