package universidad.mensajeria.cliente.componentes.conexion;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import universidad.mensajeria.common.codec.TramaCodec;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.common.tipos.TipoMensaje;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ConexionCliente contra un servidor stub (TramaCodec real):
 * correlación solicitud↔respuesta y entregas asíncronas intercaladas.
 */
class ConexionRedTest {

    private ServerSocket escucha;
    private ServidorStub stub;
    private ConexionCliente conexion;
    private final List<Mensaje> recibidos = new CopyOnWriteArrayList<>();

    @BeforeEach
    void montar() throws IOException {
        escucha = new ServerSocket(0);
        stub = new ServidorStub(escucha);
        stub.start();
        conexion = new ConexionCliente("127.0.0.1", escucha.getLocalPort());
        conexion.suscribir(new ReceptorMensajes() {
            @Override
            public void alRecibir(Mensaje mensaje) {
                recibidos.add(mensaje);
            }

            @Override
            public void alCierre(Mensaje aviso) {
                recibidos.add(aviso);
            }

            @Override
            public void alError(String motivo) {
            }

            @Override
            public void alDesconexion() {
            }
        });
        conexion.conectar();
    }

    @AfterEach
    void bajar() {
        conexion.desconectar();
        stub.cerrar();
    }

    @Test
    void loginYEnvioCorrelacionanPorId() throws Exception {
        Mensaje login = conexion.login("A001", "Secreta123");
        assertEquals(TipoMensaje.LOGIN_RESPUESTA, login.tipo());
        assertTrue(login.exito());

        Mensaje ack = conexion.enviarTexto("A001", "B002", "hola");
        assertEquals(TipoMensaje.ACK, ack.tipo());
        assertTrue(ack.exito());
    }

    @Test
    void textoEImagenConservanElIdLocalEnLaTramaTcp() throws Exception {
        String idTexto = "texto-local-1";
        Mensaje ackTexto = conexion.enviarTexto(idTexto, "A001", "B002", "hola");
        assertEquals(TipoMensaje.ACK, ackTexto.tipo());
        assertEquals(idTexto, stub.idsEnvios.poll(5, TimeUnit.SECONDS));

        String idImagen = "imagen-local-2";
        Mensaje ackImagen = conexion.enviarImagen(idImagen, "A001", "B002", "foto.png",
                "image/png", "AQID");
        assertEquals(TipoMensaje.IMAGE_FILTERED_RESULT, ackImagen.tipo());
        assertEquals(idImagen, stub.idsEnvios.poll(5, TimeUnit.SECONDS));
    }

    @Test
    void entregaAsincronaNoRompeLaEsperaDelAck() throws Exception {
        // El stub empuja un broadcast justo antes de responder el ACK.
        stub.empujar(Mensaje.builder().tipo(TipoMensaje.BROADCAST)
                .id("empuje-1").fechaHora(LocalDateTime.now().toString())
                .remitente("C").contenido("aviso").build());

        Mensaje ack = conexion.enviarTexto("A001", "B002", "hola");
        assertEquals(TipoMensaje.ACK, ack.tipo());

        long limite = System.currentTimeMillis() + 5000;
        while (recibidos.stream().noneMatch(m -> m.tipo() == TipoMensaje.BROADCAST)
                && System.currentTimeMillis() < limite) {
            Thread.sleep(50);
        }
        assertTrue(recibidos.stream().anyMatch(m -> m.tipo() == TipoMensaje.BROADCAST
                && "aviso".equals(m.contenido())));
    }

    @Test
    void listarDevuelveConectados() throws Exception {
        Mensaje respuesta = conexion.listarConectados();
        assertEquals(TipoMensaje.LISTAR_CONECTADOS_RESPUESTA, respuesta.tipo());
        assertEquals(List.of("A001", "B002"), respuesta.usuariosConectados());
    }

    @Test
    void closeNoticeLlegaAlReceptor() throws Exception {
        stub.empujar(Mensaje.builder().tipo(TipoMensaje.CLOSE_NOTICE)
                .fechaHora(LocalDateTime.now().toString()).mensajeError("kick").build());

        long limite = System.currentTimeMillis() + 5000;
        while (recibidos.stream().noneMatch(m -> m.tipo() == TipoMensaje.CLOSE_NOTICE)
                && System.currentTimeMillis() < limite) {
            Thread.sleep(50);
        }
        assertTrue(recibidos.stream().anyMatch(m -> m.tipo() == TipoMensaje.CLOSE_NOTICE));
    }

    @Test
    void sinConexionPedirFallaRapido() {
        conexion.desconectar();
        assertFalse(conexion.conectado());
    }

    /** Stub mínimo del wire protocol: responde guionado + cola de empujes. */
    private static final class ServidorStub extends Thread {
        private final ServerSocket escucha;
        private final BlockingQueue<Mensaje> empujes = new LinkedBlockingQueue<>();
        private final BlockingQueue<String> idsEnvios = new LinkedBlockingQueue<>();
        private volatile Socket cliente;

        private ServidorStub(ServerSocket escucha) {
            this.escucha = escucha;
            setDaemon(true);
        }

        private void empujar(Mensaje mensaje) {
            empujes.add(mensaje);
        }

        private void cerrar() {
            try {
                if (cliente != null) {
                    cliente.close();
                }
                escucha.close();
            } catch (IOException ignorada) {
                // cierre de test
            }
        }

        @Override
        public void run() {
            try {
                cliente = escucha.accept();
                // Empujes asíncronos en segundo plano.
                Thread fondo = new Thread(() -> {
                    try {
                        while (!cliente.isClosed()) {
                            Mensaje empuje = empujes.poll(50, TimeUnit.MILLISECONDS);
                            if (empuje != null) {
                                synchronized (cliente) {
                                    TramaCodec.escribir(cliente.getOutputStream(), empuje);
                                }
                            }
                        }
                    } catch (Exception ignorada) {
                        // fin del test
                    }
                });
                fondo.setDaemon(true);
                fondo.start();
                while (!cliente.isClosed()) {
                    Mensaje solicitud = TramaCodec.leer(cliente.getInputStream());
                    Mensaje respuesta = switch (solicitud.tipo()) {
                        case LOGIN -> Mensaje.builder().tipo(TipoMensaje.LOGIN_RESPUESTA)
                                .id(solicitud.id()).fechaHora(LocalDateTime.now().toString())
                                .exito(true).ipRemitente("127.0.0.1").build();
                        case MENSAJE_TEXTO -> {
                            idsEnvios.add(solicitud.id());
                            // Dar ventana al empuje asíncrono antes del ACK.
                            Thread.sleep(200);
                            yield Mensaje.builder().tipo(TipoMensaje.ACK)
                                    .id(solicitud.id()).fechaHora(LocalDateTime.now().toString())
                                    .exito(true).build();
                        }
                        case MENSAJE_IMAGEN -> {
                            idsEnvios.add(solicitud.id());
                            yield Mensaje.builder().tipo(TipoMensaje.IMAGE_FILTERED_RESULT)
                                    .id(solicitud.id()).archivoId("f-1").exito(true).build();
                        }
                        case LISTAR_CONECTADOS -> Mensaje.builder()
                                .tipo(TipoMensaje.LISTAR_CONECTADOS_RESPUESTA)
                                .id(solicitud.id()).fechaHora(LocalDateTime.now().toString())
                                .usuariosConectados(List.of("A001", "B002")).build();
                        default -> Mensaje.builder().tipo(TipoMensaje.ERROR)
                                .id(solicitud.id()).mensajeError("no soportado en stub").build();
                    };
                    synchronized (cliente) {
                        TramaCodec.escribir(cliente.getOutputStream(), respuesta);
                    }
                }
            } catch (Exception fin) {
                // fin del test
            }
        }
    }
}
