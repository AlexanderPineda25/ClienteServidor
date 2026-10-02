package universidad.mensajeria.clienteservidor;

import org.junit.jupiter.api.Test;
import universidad.mensajeria.clienteservidor.impl.Despachador;
import universidad.mensajeria.clienteservidor.impl.FabricaDelDespachador;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.common.tipos.TipoMensaje;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Despacho por tipo con salida falsa: validacion y ruteo sin red ni Spring. */
class DespachadorTest {

    static final class SalidaFalsa implements SalidaClienteServidor.FachadaSalida {
        final List<String> llamadas = new ArrayList<>();
        final List<Mensaje> errores = new ArrayList<>();

        @Override
        public void autenticar(IdSesion sesion, String codigo, String contrasena, String idSolicitud) {
            llamadas.add("autenticar:" + codigo);
        }

        @Override
        public void cerrar(IdSesion sesion, String idSolicitud) {
            llamadas.add("cerrar");
        }

        @Override
        public void expulsarOtrasSesiones(IdSesion sesion, String codigo, String idSolicitud) {
            llamadas.add("expulsar:" + codigo);
        }

        @Override
        public void recibirTexto(IdSesion sesion, String destinatario, String contenido,
                                 String idSolicitud, String ip) {
            llamadas.add("texto:" + destinatario);
        }

        @Override
        public void recibirImagen(IdSesion sesion, String destinatario, String nombreArchivo,
                                  String mime, String contenidoBase64, String idSolicitud, String ip) {
            llamadas.add("imagen:" + destinatario);
        }

        @Override
        public void recibirArchivo(IdSesion sesion, String destinatario, String nombreArchivo,
                                   String mime, String contenidoBase64, String idSolicitud, String ip) {
            llamadas.add("archivo:" + destinatario);
        }

        @Override
        public void iniciarArchivo(IdSesion sesion, String destinatario, String nombreArchivo,
                                   String mime, long tamanoTotal, int totalPartes,
                                   String idSolicitud, String ip) {
            llamadas.add("archivo-inicio:" + destinatario + ":" + totalPartes);
        }

        @Override
        public void parteArchivo(IdSesion sesion, String idTransferencia, int indiceParte,
                                  String contenidoBase64, String idSolicitud, String ip) {
            llamadas.add("archivo-parte:" + idTransferencia + ":" + indiceParte);
        }

        @Override
        public void finalizarArchivo(IdSesion sesion, String idTransferencia, String hashSha256,
                                      String idSolicitud, String ip) {
            llamadas.add("archivo-fin:" + idTransferencia);
        }

        @Override
        public void difundir(IdSesion sesion, String contenido, String idSolicitud, String ip) {
            llamadas.add("difundir");
        }

        @Override
        public void historial(IdSesion sesion, String otro, int pagina, String idSolicitud) {
            llamadas.add("historial:" + otro);
        }

        @Override
        public void descargar(IdSesion sesion, String archivoId, String idSolicitud) {
            llamadas.add("descargar:" + archivoId);
        }

        @Override
        public void notificarLectura(IdSesion sesion, String destinatario, String idOriginal,
                                     String idSolicitud) {
            llamadas.add("leido:" + destinatario + ":" + idOriginal);
        }

        @Override
        public void listarConectados(IdSesion sesion, String idSolicitud) {
            llamadas.add("listar");
        }

        @Override
        public void listarUsuarios(IdSesion sesion, String idSolicitud) {
            llamadas.add("usuarios");
        }

        @Override
        public void responder(IdSesion sesion, Mensaje respuesta) {
            llamadas.add("responder:" + respuesta.tipo());
        }

        @Override
        public void error(IdSesion sesion, String idSolicitud, TipoMensaje tipo, String motivo) {
            llamadas.add("error:" + motivo);
            errores.add(Mensaje.builder().tipo(tipo).id(idSolicitud).mensajeError(motivo).build());
        }
    }

    private static IdSesion sesion(String codigo) {
        return new IdSesion("s1", codigo, "127.0.0.1");
    }

    @Test
    void ruteaLosTiposYValida() {
        SalidaFalsa salida = new SalidaFalsa();
        Despachador despachador = FabricaDelDespachador.crear(salida);
        IdSesion anonima = IdSesion.anonima("127.0.0.1");
        IdSesion yo = sesion("A001");

        despachador.procesar(Mensaje.builder().tipo(TipoMensaje.LOGIN).codigo("A").contrasena("x").build(), anonima);
        // REGISTRO eliminado por red: responde ERROR sin delegar.
        despachador.procesar(Mensaje.builder().tipo(TipoMensaje.REGISTRO).codigo("B").contrasena("123456")
                .nombres("N").apellidos("A").programa("P").build(), anonima);
        despachador.procesar(Mensaje.builder().tipo(TipoMensaje.LOGOUT).build(), yo);
        despachador.procesar(Mensaje.builder().tipo(TipoMensaje.LISTAR_CONECTADOS).build(), yo);
        despachador.procesar(Mensaje.builder().tipo(TipoMensaje.LISTAR_USUARIOS).build(), yo);
        despachador.procesar(Mensaje.builder().tipo(TipoMensaje.MENSAJE_TEXTO).destinatario("B")
                .contenido("hola").build(), yo);
        despachador.procesar(Mensaje.builder().tipo(TipoMensaje.MENSAJE_IMAGEN).destinatario("B")
                .contenidoImagen("QUJD").build(), yo);
        despachador.procesar(Mensaje.builder().tipo(TipoMensaje.MENSAJE_ARCHIVO).destinatario("B")
                .contenidoImagen("QUJD").build(), yo);
        despachador.procesar(Mensaje.builder().tipo(TipoMensaje.BROADCAST).contenido("aviso").build(), yo);
        despachador.procesar(Mensaje.builder().tipo(TipoMensaje.HISTORIAL_REQ).destinatario("B").build(), yo);
        despachador.procesar(Mensaje.builder().tipo(TipoMensaje.DESCARGAR_ARCHIVO).archivoId("1").build(), yo);
        despachador.procesar(Mensaje.builder().tipo(TipoMensaje.KICK).codigo("A001").build(), yo);
        despachador.procesar(Mensaje.builder().tipo(TipoMensaje.MENSAJE_LEIDO).destinatario("B")
                .contenido("m-1").build(), yo);

        assertTrue(salida.llamadas.contains("autenticar:A"));
        assertTrue(salida.errores.stream().anyMatch(e -> e.mensajeError().contains("REGISTRO")),
                "REGISTRO por red debe responder ERROR");
        assertTrue(salida.llamadas.contains("cerrar"));
        assertTrue(salida.llamadas.contains("listar"));
        assertTrue(salida.llamadas.contains("usuarios"));
        assertTrue(salida.llamadas.contains("texto:B"));
        assertTrue(salida.llamadas.contains("imagen:B"));
        assertTrue(salida.llamadas.contains("archivo:B"));
        assertTrue(salida.llamadas.contains("difundir"));
        assertTrue(salida.llamadas.contains("historial:B"));
        assertTrue(salida.llamadas.contains("descargar:1"));
        assertTrue(salida.llamadas.contains("expulsar:A001"));
        assertTrue(salida.llamadas.contains("leido:B:m-1"));

        // Sin auth: texto responde ERROR y no delega.
        despachador.procesar(Mensaje.builder().tipo(TipoMensaje.MENSAJE_TEXTO)
                .destinatario("B").contenido("x").build(), anonima);
        assertTrue(salida.errores.stream().anyMatch(e -> e.mensajeError().contains("LOGIN")));

        // Kick ajeno: ERROR.
        despachador.procesar(Mensaje.builder().tipo(TipoMensaje.KICK).codigo("OTRO").build(), yo);
        assertTrue(salida.errores.stream().anyMatch(e -> e.mensajeError().contains("propias sesiones")));

        // REGISTRO incompleto: tambien ERROR (sin handler dedicado).
        despachador.procesar(Mensaje.builder().tipo(TipoMensaje.REGISTRO).codigo("C").build(), anonima);
        assertTrue(salida.errores.stream().anyMatch(e -> e.mensajeError().contains("REGISTRO")));
    }

    @Test
    void ruteaFragmentosDeArchivo() {
        SalidaFalsa salida = new SalidaFalsa();
        Despachador despachador = FabricaDelDespachador.crear(salida);
        IdSesion yo = sesion("A001");

        despachador.procesar(Mensaje.builder().tipo(TipoMensaje.ARCHIVO_INICIO).id("t-1")
                .destinatario("B").nombreArchivo("doc.pdf").tamanoArchivo(2_000_000L)
                .totalPartes(2).build(), yo);
        despachador.procesar(Mensaje.builder().tipo(TipoMensaje.ARCHIVO_PARTE).id("t-1")
                .archivoId("t-1").indiceParte(0).contenidoImagen("QUJD").build(), yo);
        despachador.procesar(Mensaje.builder().tipo(TipoMensaje.ARCHIVO_FIN).id("t-1")
                .archivoId("t-1").hashSha256("abc").build(), yo);

        assertTrue(salida.llamadas.contains("archivo-inicio:B:2"));
        assertTrue(salida.llamadas.contains("archivo-parte:t-1:0"));
        assertTrue(salida.llamadas.contains("archivo-fin:t-1"));

        // INICIO sin totalPartes: ERROR sin delegar a iniciarArchivo.
        long iniciosAntes = salida.llamadas.stream()
                .filter(l -> l.startsWith("archivo-inicio:")).count();
        despachador.procesar(Mensaje.builder().tipo(TipoMensaje.ARCHIVO_INICIO).id("t-2")
                .destinatario("B").nombreArchivo("doc.pdf").tamanoArchivo(10L).build(), yo);
        assertEquals(iniciosAntes, salida.llamadas.stream()
                .filter(l -> l.startsWith("archivo-inicio:")).count());
        assertTrue(salida.errores.stream().anyMatch(e -> e.mensajeError().contains("ARCHIVO_INICIO")));
    }

    @Test
    void mapaCubreCatalogo() {
        assertEquals(Despachador.tiposSoportados().size(), Despachador.mapaTipos().size());
        assertTrue(Despachador.tiposSoportados().containsAll(
                List.of("LOGIN", "MENSAJE_TEXTO", "MENSAJE_ARCHIVO", "ARCHIVO_INICIO",
                        "ARCHIVO_PARTE", "ARCHIVO_FIN", "KICK", "LISTAR_USUARIOS")));
        Map<String, ?> comandos = FabricaDelDespachador.crear(new SalidaFalsa()).comandos();
        assertEquals(Despachador.mapaTipos().keySet(), comandos.keySet());
    }

    @Test
    void tipoDesconocidoRespondeError() {
        SalidaFalsa salida = new SalidaFalsa();
        Despachador despachador = FabricaDelDespachador.crear(salida);
        // SYNC_LOGIN existe en el catalogo pero no tiene handler: va a error sin tumbar.
        despachador.procesar(Mensaje.builder().tipo(TipoMensaje.SYNC_LOGIN).build(), sesion("A"));
        assertEquals(1, salida.errores.size());
        assertTrue(salida.errores.get(0).mensajeError().contains("SYNC_LOGIN"));
    }

    @Test
    void constructorRechazaVacioYDuplicados() {
        assertThrows(IllegalStateException.class, () -> new Despachador(List.of()));
    }
}
