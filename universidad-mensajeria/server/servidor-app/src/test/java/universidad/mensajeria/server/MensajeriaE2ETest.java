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
import universidad.mensajeria.server.inicio.Fabrica;
import universidad.mensajeria.server.inicio.SemillaUsuarios;
import universidad.mensajeria.server.almacenarinformacion.PersistenciaPort;
import universidad.mensajeria.server.transversal.config.FiltrosProperties;
import universidad.mensajeria.server.transversal.config.RedProperties;
import universidad.mensajeria.server.transversal.config.SesionProperties;
import universidad.mensajeria.server.transversal.fachada.Fachada;
import universidad.mensajeria.usuarios.InterfazUsuariosDisponibles;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FASE 4 — criterios §13.3 y §13.7 sobre TCP real:
 * texto 1-a-1 con ACK y entrega, multi-conexión, broadcast a todos,
 * imagen con IMAGE_FILTERED_RESULT, KICK propio, cierre por corte de red
 * y comandos registrados. Requiere MySQL (docker compose up mysql).
 */
@SpringBootTest(properties = {"app.inicio=false", "app.consola=false", "app.escritorio=false",
        "app.salir=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MensajeriaE2ETest {

    private static final int TIMEOUT_MS = 8000;
    private static final String PASS_SEED = "Secreta123";
    private static final String A = "A001234567";
    private static final String B = "B002345678";
    private static final String C = "C003456789";
    private static final String D = "D004567890";

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
    static Path baseTemporal;
    private Path dirUploads;
    private Path dirWork;

    @BeforeAll
    void subirServidor() throws Exception {
        List<UsuarioDTO> semilla = SemillaUsuarios.leer(
                resources.getResource("classpath:usuarios_iniciales.csv").getInputStream(),
                passwordEncoder::encode);
        dirUploads = Files.createDirectories(baseTemporal.resolve("uploads"));
        dirWork = Files.createDirectories(baseTemporal.resolve("work"));
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
    void despachadorCubreCatalogoMensajeria() {
        // Los 12 tipos con handler se verifican en DespachadorTest (comp);
        // aqui: el servidor vivo responde al ciclo completo texto/imagen/broadcast.
        assertTrue(fachada.estadoServidor().activo());
        assertEquals(red.puerto(), fachada.estadoServidor().puerto());
    }

    @Test
    void textoUnoAUnoConAckYEntregaConHash() throws Exception {
        try (ClientePrueba a = conectar(); ClientePrueba b = conectar()) {
            assertTrue(login(a, A, PASS_SEED).exito());
            assertTrue(login(b, B, PASS_SEED).exito());
            int enColaAntes = fachada.mensajesEnCola();

            a.enviar(Mensaje.builder().tipo(TipoMensaje.MENSAJE_TEXTO)
                    .id("txt-1").fechaHora(LocalDateTime.now().toString())
                    .remitente(A).destinatario(B).contenido("hola mundo").build());

            Mensaje ack = a.leerSalvoSync();
            assertEquals(TipoMensaje.ACK, ack.tipo());
            assertEquals("txt-1", ack.id());
            assertTrue(ack.exito());

            Mensaje entrega = b.leerSalvoSync();
            assertEquals(TipoMensaje.MENSAJE_TEXTO, entrega.tipo());
            assertEquals(A, entrega.remitente());
            assertEquals("hola mundo", entrega.contenido());
            assertEquals("0b894166d3336435c800bea36ff21b29eaa801a52f584c006c49289a0dcf6e2f",
                    entrega.hashSha256());
            assertEquals(10, entrega.numCaracteres());
            assertEquals(2, entrega.numPalabras());

            assertTrue(fachada.mensajesEnCola() > enColaAntes,
                    "la entrega debe pasar por GestorColasDeMensajes");
        }
    }

    @Test
    void textoLlegaATodasLasSesionesDelMismoCodigo() throws Exception {
        try (ClientePrueba a = conectar();
             ClientePrueba b1 = conectar();
             ClientePrueba b2 = conectar()) {
            assertTrue(login(a, A, PASS_SEED).exito());
            assertTrue(login(b1, B, PASS_SEED).exito());
            assertTrue(login(b2, B, PASS_SEED).exito());

            a.enviar(Mensaje.builder().tipo(TipoMensaje.MENSAJE_TEXTO)
                    .id("txt-multi").fechaHora(LocalDateTime.now().toString())
                    .destinatario(B).contenido("para ambas sesiones").build());
            assertEquals(TipoMensaje.ACK, a.leerSalvoSync().tipo());

            assertEquals("para ambas sesiones", b1.leerSalvoSync().contenido());
            assertEquals("para ambas sesiones", b2.leerSalvoSync().contenido());
        }
    }

    @Test
    void textoSinLoginYDestinatarioDesconocidoRespondenError() throws Exception {
        try (ClientePrueba anonimo = conectar(); ClientePrueba a = conectar()) {
            anonimo.enviar(Mensaje.builder().tipo(TipoMensaje.MENSAJE_TEXTO)
                    .id("txt-x").fechaHora(LocalDateTime.now().toString())
                    .destinatario(B).contenido("hola").build());
            Mensaje error = anonimo.leer();
            assertEquals(TipoMensaje.ERROR, error.tipo());
            assertTrue(error.mensajeError().contains("LOGIN"));

            assertTrue(login(a, A, PASS_SEED).exito());
            a.enviar(Mensaje.builder().tipo(TipoMensaje.MENSAJE_TEXTO)
                    .id("txt-y").fechaHora(LocalDateTime.now().toString())
                    .destinatario("ZZZ000000").contenido("hola").build());
            Mensaje noExiste = a.leerSalvoSync();
            assertEquals(TipoMensaje.ERROR, noExiste.tipo());
        }
    }

    @Test
    void broadcastLlegaATodosSalvoAlEmisor() throws Exception {
        try (ClientePrueba emisor = conectar();
             ClientePrueba misma1 = conectar();
             ClientePrueba otro = conectar()) {
            assertTrue(login(emisor, D, PASS_SEED).exito());
            assertTrue(login(misma1, D, PASS_SEED).exito());
            assertTrue(login(otro, C, PASS_SEED).exito());

            emisor.enviar(Mensaje.builder().tipo(TipoMensaje.BROADCAST)
                    .id("bc-1").fechaHora(LocalDateTime.now().toString())
                    .contenido("aviso general").build());

            assertEquals(TipoMensaje.ACK, emisor.leerSalvoSync().tipo());

            Mensaje aMisma = misma1.leerSalvoSync();
            assertEquals(TipoMensaje.BROADCAST, aMisma.tipo());
            assertEquals("aviso general", aMisma.contenido());
            assertEquals(D, aMisma.remitente());

            Mensaje aOtro = otro.leerSalvoSync();
            assertEquals(TipoMensaje.BROADCAST, aOtro.tipo());
            assertEquals("aviso general", aOtro.contenido());
        }
    }

    @Test
    void broadcastAdministrativoLlegaASesionesSinUsuarioDeSistema() throws Exception {
        try (ClientePrueba destino = conectar()) {
            assertTrue(login(destino, C, PASS_SEED).exito());

            fachada.difundirAdministrativo("Mantenimiento programado");

            Mensaje aviso = destino.leerSalvoSync();
            assertEquals(TipoMensaje.BROADCAST, aviso.tipo());
            assertEquals("SERVIDOR", aviso.remitente());
            assertEquals("Mantenimiento programado", aviso.contenido());
        }
    }

    @Test
    void imagenDevuelveFiltrosYEntregaAlDestinatario() throws Exception {
        try (ClientePrueba a = conectar(); ClientePrueba b = conectar()) {
            assertTrue(login(a, A, PASS_SEED).exito());
            assertTrue(login(b, B, PASS_SEED).exito());

            BufferedImage imagen = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    imagen.setRGB(x, y, ((x * 32) << 16) | ((y * 32) << 8) | 128);
                }
            }
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(imagen, "png", png);
            String base64 = Base64.getEncoder().encodeToString(png.toByteArray());

            a.enviar(Mensaje.builder().tipo(TipoMensaje.MENSAJE_IMAGEN)
                    .id("img-1").fechaHora(LocalDateTime.now().toString())
                    .destinatario(B).nombreArchivo("foto.png").mime("image/png")
                    .contenidoImagen(base64).build());

            Mensaje resultado = a.leerSalvoSync();
            assertEquals(TipoMensaje.IMAGE_FILTERED_RESULT, resultado.tipo());
            assertEquals("img-1", resultado.id());
            assertTrue(resultado.hashSha256() != null && resultado.hashSha256().length() == 64);
            assertTrue(resultado.etapasFiltrado().contains("01_grises.png"));
            assertTrue(resultado.etapasFiltrado().contains("05_reduccion.png"));
            assertTrue(resultado.archivoId() != null && !resultado.archivoId().isBlank());

            Mensaje entrega = b.leerSalvoSync();
            assertEquals(TipoMensaje.MENSAJE_IMAGEN, entrega.tipo());
            assertEquals(base64, entrega.contenidoImagen());
            assertEquals(resultado.hashSha256(), entrega.hashSha256());

            // El paquete vive en uploads/<propietario>/<hash>/ (carpeta por remitente).
            Path carpeta;
            try (var flujos = Files.walk(dirUploads, 3)) {
                carpeta = flujos
                        .filter(p -> p.getFileName().toString().equals(resultado.hashSha256()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(
                                "falta carpeta del hash en uploads: " + resultado.hashSha256()));
            }
            for (String archivo : List.of("00_original.png", "01_grises.png", "02_sepia.png",
                    "03_giro180.png", "04_brillo.png", "05_reduccion.png")) {
                assertTrue(Files.isRegularFile(carpeta.resolve(archivo)),
                        "falta derivado en uploads: " + archivo);
            }
        }
    }

    @Test
    void kickCierraLasOtrasSesionesPropiasYAvisoAOtroCodigoEsError() throws Exception {
        try (ClientePrueba primera = conectar(); ClientePrueba segunda = conectar()) {
            assertTrue(login(primera, D, PASS_SEED).exito());
            assertTrue(login(segunda, D, PASS_SEED).exito());

            primera.enviar(Mensaje.builder().tipo(TipoMensaje.KICK)
                    .id("kick-1").fechaHora(LocalDateTime.now().toString()).codigo(D).build());
            assertEquals(TipoMensaje.ACK, primera.leerSalvoSync().tipo());

            Mensaje aviso = segunda.leerSalvoSync();
            assertEquals(TipoMensaje.CLOSE_NOTICE, aviso.tipo());
            assertThrows(IOException.class, () -> segunda.leer(),
                    "la sesion expulsada debe quedar cerrada");

            primera.enviar(Mensaje.builder().tipo(TipoMensaje.KICK)
                    .id("kick-2").fechaHora(LocalDateTime.now().toString()).codigo(A).build());
            Mensaje error = primera.leerSalvoSync();
            assertEquals(TipoMensaje.ERROR, error.tipo());
        }
    }

    @Test
    void corteAbruptoLiberaSoloLaSesionAfectada() throws Exception {
        ClientePrueba primera = conectar();
        try (ClientePrueba segunda = conectar()) {
            assertTrue(login(primera, D, PASS_SEED).exito());
            assertTrue(login(segunda, D, PASS_SEED).exito());

            primera.close();

            segunda.enviar(Mensaje.builder().tipo(TipoMensaje.LISTAR_CONECTADOS)
                    .id("listar-tras-corte").fechaHora(LocalDateTime.now().toString()).build());
            Mensaje respuesta = segunda.leerSalvoSync();
            assertEquals(TipoMensaje.LISTAR_CONECTADOS_RESPUESTA, respuesta.tipo());
            assertTrue(respuesta.usuariosConectados().contains(D));
            esperar(() -> fachada.estadoServidor().sesionesActivas() == 1);
        } finally {
            primera.close();
        }
    }

    @Test
    void historialPaginadoDevuelveConversacionSinBytes() throws Exception {
        String emisor = registrarUsuarioNuevo();
        String receptor = registrarUsuarioNuevo();
        try (ClientePrueba a = conectar(); ClientePrueba b = conectar()) {
            assertTrue(login(a, emisor, "Nueva123").exito());
            a.enviar(Mensaje.builder().tipo(TipoMensaje.MENSAJE_TEXTO)
                    .id("hist-1").fechaHora(LocalDateTime.now().toString())
                    .destinatario(receptor).contenido("hola historial").build());
            assertEquals(TipoMensaje.ACK, a.leerSalvoSync().tipo());

            assertTrue(login(b, receptor, "Nueva123").exito());
            b.enviar(Mensaje.builder().tipo(TipoMensaje.HISTORIAL_REQ)
                    .id("hist-req-1").fechaHora(LocalDateTime.now().toString())
                    .destinatario(emisor).pagina(0).build());

            Mensaje pagina = b.leerSalvoSync();
            assertEquals(TipoMensaje.HISTORIAL_PAGE, pagina.tipo());
            assertEquals("hist-req-1", pagina.id());
            assertEquals(0, pagina.pagina());
            assertTrue(pagina.contenido().contains("hola historial"));
            assertFalse(pagina.contenido().contains("contenidoImagen"),
                    "el historial nunca trae bytes de imagen");
        }
    }

    @Test
    void descargaArchivoDevuelveBytesOriginales() throws Exception {
        String emisor = registrarUsuarioNuevo();
        String receptor = registrarUsuarioNuevo();
        try (ClientePrueba a = conectar(); ClientePrueba b = conectar()) {
            assertTrue(login(a, emisor, "Nueva123").exito());

            BufferedImage imagen = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    imagen.setRGB(x, y, ((x * 16) << 16) | ((y * 16) << 8) | 64);
                }
            }
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(imagen, "png", png);
            String base64 = Base64.getEncoder().encodeToString(png.toByteArray());

            a.enviar(Mensaje.builder().tipo(TipoMensaje.MENSAJE_IMAGEN)
                    .id("img-dl-1").fechaHora(LocalDateTime.now().toString())
                    .destinatario(receptor).nombreArchivo("dl.png").mime("image/png")
                    .contenidoImagen(base64).build());
            Mensaje resultado = a.leerSalvoSync();
            assertEquals(TipoMensaje.IMAGE_FILTERED_RESULT, resultado.tipo());

            assertTrue(login(b, receptor, "Nueva123").exito());
            // B estaba offline al enviarse: no hay entrega en vivo (el SYNC ya
            // lo consumio el drain); la imagen se obtiene bajo demanda.
            b.enviar(Mensaje.builder().tipo(TipoMensaje.DESCARGAR_ARCHIVO)
                    .id("dl-1").fechaHora(LocalDateTime.now().toString())
                    .archivoId(resultado.archivoId()).build());
            Mensaje descarga = b.leerSalvoSync();
            assertEquals(TipoMensaje.DESCARGAR_ARCHIVO_RESPUESTA, descarga.tipo());
            assertEquals("dl-1", descarga.id());
            assertEquals(base64, descarga.contenidoImagen());
            assertEquals("dl.png", descarga.nombreArchivo());
        }
    }

    @Test
    void syncLoginEntregaPendientesAlEntrar() throws Exception {        String emisor = registrarUsuarioNuevo();
        String receptor = registrarUsuarioNuevo();
        try (ClientePrueba a = conectar()) {
            assertTrue(login(a, emisor, "Nueva123").exito());
            a.enviar(Mensaje.builder().tipo(TipoMensaje.MENSAJE_TEXTO)
                    .id("sync-1").fechaHora(LocalDateTime.now().toString())
                    .destinatario(receptor).contenido("te escribi offline").build());
            assertEquals(TipoMensaje.ACK, a.leerSalvoSync().tipo());
        }

        // El receptor entra DESPUES: tras LOGIN_RESPUESTA debe llegar el SYNC.
        try (ClientePrueba b = conectar()) {
            b.enviar(Mensaje.builder().tipo(TipoMensaje.LOGIN)
                    .id("login-sync").fechaHora(LocalDateTime.now().toString())
                    .codigo(receptor).contrasena("Nueva123").build());
            Mensaje respuesta = b.leer();
            assertEquals(TipoMensaje.LOGIN_RESPUESTA, respuesta.tipo());
            assertTrue(respuesta.exito());

            Mensaje sync = b.leer();
            assertEquals(TipoMensaje.SYNC_LOGIN, sync.tipo());
            assertEquals(emisor, sync.remitente());
            assertEquals("te escribi offline", sync.contenido());
        }
    }

    @Test
    void archivoGenericoSoloCalculaHashYSeEntrega() throws Exception {
        String emisor = registrarUsuarioNuevo();
        String receptor = registrarUsuarioNuevo();
        String base64 = Base64.getEncoder().encodeToString("contenido-falso-de-pdf".getBytes());
        try (ClientePrueba a = conectar(); ClientePrueba b = conectar()) {
            assertTrue(login(a, emisor, "Nueva123").exito());
            assertTrue(login(b, receptor, "Nueva123").exito());

            a.enviar(Mensaje.builder().tipo(TipoMensaje.MENSAJE_ARCHIVO)
                    .id("arc-1").fechaHora(LocalDateTime.now().toString())
                    .destinatario(receptor).nombreArchivo("doc.pdf")
                    .mime("application/pdf").contenidoImagen(base64).build());

            Mensaje ack = a.leerSalvoSync();
            assertEquals(TipoMensaje.ACK, ack.tipo());
            assertEquals("arc-1", ack.id());
            assertTrue(ack.exito());

            Mensaje entrega = b.leerSalvoSync();
            assertEquals(TipoMensaje.MENSAJE_ARCHIVO, entrega.tipo());
            assertEquals(base64, entrega.contenidoImagen());
            assertTrue(entrega.hashSha256() != null && entrega.hashSha256().length() == 64);
        }
    }

    @Test
    void listarUsuariosDevuelveDirectorioConEstado() throws Exception {
        try (ClientePrueba a = conectar()) {
            assertTrue(login(a, A, PASS_SEED).exito());

            a.enviar(Mensaje.builder().tipo(TipoMensaje.LISTAR_USUARIOS)
                    .id("lu-1").fechaHora(LocalDateTime.now().toString()).build());

            Mensaje respuesta = a.leerSalvoSync();
            assertEquals(TipoMensaje.LISTAR_USUARIOS_RESPUESTA, respuesta.tipo());
            assertEquals("lu-1", respuesta.id());
            assertTrue(respuesta.contenido() != null && respuesta.contenido().contains(A));
        }
    }

    // ------------------------------------------------------------------ util

    private ClientePrueba conectar() throws IOException {
        return new ClientePrueba(puerto);
    }

    /**
     * Alta de usuario unico por test (aisla conversaciones y SYNCs): alta
     * directa en memoria + persistencia, sin via de red (eliminada).
     */
    private String registrarUsuarioNuevo() {
        String codigo = "U" + Long.toString(System.currentTimeMillis(), 36).toUpperCase()
                + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        UsuarioDTO creado = sistema.usuarios().registrar(codigo,
                passwordEncoder.encode("Nueva123"), "E2E", "Fase6", "Ingenieria de Sistemas");
        if (!sistema.almacenar().existeUsuarioPorCodigo(creado.codigo())) {
            sistema.almacenar().registrarUsuarioNuevo(creado.codigo(), creado.hashContrasena(),
                    creado.nombres(), creado.apellidos(), creado.programa());
        }
        return codigo;
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
            // PRESENCIA / SYNC async: se consumen y se sigue esperando la respuesta
        }
    }

    private static void esperar(BooleanSupplier condicion) throws InterruptedException {
        long limite = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < limite && !condicion.getAsBoolean()) {
            Thread.sleep(50);
        }
        assertTrue(condicion.getAsBoolean(), "la condicion no se cumplio en " + TIMEOUT_MS + " ms");
    }

    /** Cliente TCP minimo sobre el wire protocol (igual que en LoginListarE2ETest). */
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
         * Lee saltando tramas async (SYNC_LOGIN, PRESENCIA, ENTREGADO, LEIDO:
         * llegan en momento no determinista; los clientes reales los consumen async).
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
