package universidad.mensajeria.protocolo.impl;

import org.junit.jupiter.api.Test;
import universidad.mensajeria.common.codec.TramaCodec;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.common.tipos.TipoMensaje;
import universidad.mensajeria.protocolo.ConfigRed;
import universidad.mensajeria.protocolo.InterfazProtocoloComunicacion;
import universidad.mensajeria.protocolo.ReceptorDeTramas;

import java.io.EOFException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Transporte sin Spring: acepta, notifica al receptor y cierra por inactividad. */
class ProtocoloComunicacionTest {

    @Test
    void aceptaNotificaYcierraPorInactividad() throws Exception {
        List<String> eventos = new ArrayList<>();
        InterfazProtocoloComunicacion protocolo =
                new ProveedorProtocoloComunicacionImpl().crear(new ConfigRed(0, 2, 4, 10, 1));
        protocolo.registrarReceptor(new ReceptorDeTramas() {
            @Override
            public void alConectar(IdSesion sesion) {
                eventos.add("conecta");
            }

            @Override
            public void alRecibir(IdSesion sesion, Mensaje mensaje) {
                eventos.add("recibe:" + mensaje.tipo());
            }

            @Override
            public void alDesconectar(IdSesion sesion, String motivo) {
                eventos.add("desconecta:" + motivo);
            }
        });
        protocolo.iniciar();
        try {
            assertTrue(protocolo.estaActivo());
            try (Socket cliente = new Socket("127.0.0.1", protocolo.puerto())) {
                cliente.setSoTimeout(10000);
                Mensaje aviso = TramaCodec.leer(cliente.getInputStream());
                assertEquals(TipoMensaje.CLOSE_NOTICE, aviso.tipo());
                assertTrue(aviso.mensajeError().contains("inactividad"));
                assertThrows(EOFException.class,
                        () -> TramaCodec.leer(cliente.getInputStream()));
            }
            Thread.sleep(300);
            assertTrue(eventos.contains("conecta"));
            assertTrue(eventos.stream().anyMatch(e -> e.startsWith("desconecta:")));
            assertEquals(0, ((ProtocoloComunicacion) protocolo).sesionesVivas());
        } finally {
            protocolo.detener();
        }
    }

    @Test
    void cerrarSesionDesconocidaEsIdempotente() {
        InterfazProtocoloComunicacion protocolo =
                new ProveedorProtocoloComunicacionImpl().crear(new ConfigRed(0, 1, 1, 1, 300));
        protocolo.cerrarSesion(new IdSesion("inexistente", null, "0.0.0.0"), "motivo");
        assertThrows(IllegalArgumentException.class, () -> {
            try {
                protocolo.enviar(new IdSesion("inexistente", null, "0.0.0.0"),
                        Mensaje.de(TipoMensaje.ACK));
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void poolRechazaSaturacionYReutilizaElTrabajadorLiberado() throws Exception {
        InterfazProtocoloComunicacion protocolo =
                new ProveedorProtocoloComunicacionImpl().crear(new ConfigRed(0, 1, 1, 1, 1));
        protocolo.iniciar();
        try (Socket primera = new Socket("127.0.0.1", protocolo.puerto())) {
            primera.setSoTimeout(4000);
            esperar(() -> protocolo.estadoPool().ocupados() == 1);
            assertEquals(0, protocolo.estadoPool().disponibles());

            try (Socket rechazada = new Socket("127.0.0.1", protocolo.puerto())) {
                rechazada.setSoTimeout(2000);
                Mensaje error = TramaCodec.leer(rechazada.getInputStream());
                assertEquals(TipoMensaje.ERROR, error.tipo());
                assertTrue(error.mensajeError().contains("servidor lleno"));
            }

            primera.close();
            esperar(() -> protocolo.estadoPool().disponibles() == 1);

            try (Socket reutilizada = new Socket("127.0.0.1", protocolo.puerto())) {
                reutilizada.setSoTimeout(3000);
                Mensaje aviso = TramaCodec.leer(reutilizada.getInputStream());
                assertEquals(TipoMensaje.CLOSE_NOTICE, aviso.tipo());
                assertTrue(aviso.mensajeError().contains("inactividad"));
            }
            esperar(() -> protocolo.estadoPool().disponibles() == 1);
            assertEquals(1, protocolo.estadoPool().total());
        } finally {
            protocolo.detener();
        }
    }

    @Test
    void rechazoPorSaturacionAvisaAlReceptor() throws Exception {
        List<String> avisos = new ArrayList<>();
        InterfazProtocoloComunicacion protocolo =
                new ProveedorProtocoloComunicacionImpl().crear(new ConfigRed(0, 1, 1, 1, 300));
        protocolo.registrarReceptor(new ReceptorDeTramas() {
            @Override
            public void alConectar(IdSesion sesion) {
            }

            @Override
            public void alRecibir(IdSesion sesion, Mensaje mensaje) {
            }

            @Override
            public void alDesconectar(IdSesion sesion, String motivo) {
            }

            @Override
            public void alRechazoPorSaturacion(String ip, int maximo) {
                avisos.add(ip + ":" + maximo);
            }
        });
        protocolo.iniciar();
        try (Socket primera = new Socket("127.0.0.1", protocolo.puerto())) {
            primera.setSoTimeout(4000);
            esperar(() -> protocolo.estadoPool().ocupados() == 1);
            try (Socket rechazada = new Socket("127.0.0.1", protocolo.puerto())) {
                rechazada.setSoTimeout(2000);
                Mensaje error = TramaCodec.leer(rechazada.getInputStream());
                assertEquals(TipoMensaje.ERROR, error.tipo());
            }
            esperar(() -> !avisos.isEmpty());
            assertTrue(avisos.get(0).endsWith(":1"));
        } finally {
            protocolo.detener();
        }
    }

    @Test
    void rafagaSobreLaTasaRespondeErrorDeFrecuencia() throws Exception {
        InterfazProtocoloComunicacion protocolo =
                new ProveedorProtocoloComunicacionImpl().crear(new ConfigRed(0, 2, 4, 10, 300));
        protocolo.registrarReceptor(new ReceptorDeTramas() {
            @Override
            public void alConectar(IdSesion sesion) {
            }

            @Override
            public void alRecibir(IdSesion sesion, Mensaje mensaje) {
            }

            @Override
            public void alDesconectar(IdSesion sesion, String motivo) {
            }
        });
        protocolo.iniciar();
        try (Socket cliente = new Socket("127.0.0.1", protocolo.puerto())) {
            cliente.setSoTimeout(3000);
            // Rafaga de 40 tramas sin esperar respuesta: supera la rafaga inicial (20).
            // El receptor falso no responde; solo llegan los ERROR de frecuencia.
            for (int i = 0; i < 40; i++) {
                TramaCodec.escribir(cliente.getOutputStream(), Mensaje.builder()
                        .tipo(TipoMensaje.LISTAR_CONECTADOS)
                        .id("rafaga-" + i).build());
            }
            int limitadas = 0;
            try {
                while (true) {
                    Mensaje respuesta = TramaCodec.leer(cliente.getInputStream());
                    if (respuesta.mensajeError() != null
                            && respuesta.mensajeError().contains("frecuencia")) {
                        limitadas++;
                    }
                }
            } catch (java.net.SocketTimeoutException fin) {
                // Sin mas respuestas: fin de la rafaga.
            }
            assertTrue(limitadas > 0, "la rafaga debio limitarse por frecuencia");
        } finally {
            protocolo.detener();
        }
    }

    private static void esperar(java.util.function.BooleanSupplier condicion) throws Exception {
        long limite = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (!condicion.getAsBoolean() && System.nanoTime() < limite) Thread.sleep(10);
        assertTrue(condicion.getAsBoolean(), "se agotó el tiempo esperando el estado del pool");
    }
}
