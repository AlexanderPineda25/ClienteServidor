package universidad.mensajeria.common.codec;

import org.junit.jupiter.api.Test;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.common.tipos.TipoMensaje;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TramaCodecTest {

    @Test
    void idaYVueltaCompleta() {
        Mensaje original = Mensaje.builder()
                .tipo(TipoMensaje.MENSAJE_TEXTO)
                .id("abc-123")
                .fechaHora("2026-09-30T10:00:00")
                .remitente("A001")
                .destinatario("A002")
                .contenido("Hola comunidad academica")
                .hashSha256("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
                .numCaracteres(25)
                .numPalabras(4)
                .usuariosConectados(List.of("A001", "A002"))
                .build();

        Mensaje recibido = TramaCodec.decodificar(TramaCodec.codificar(original));

        assertEquals(original, recibido);
    }

    @Test
    void tramaEmpiezaConLongitudBigEndian() {
        byte[] trama = TramaCodec.codificar(Mensaje.de(TipoMensaje.ACK));
        int longitud = ((trama[0] & 0xFF) << 24) | ((trama[1] & 0xFF) << 16)
                | ((trama[2] & 0xFF) << 8) | (trama[3] & 0xFF);
        assertEquals(trama.length - 4, longitud);
    }

    @Test
    void camposNulosNoSeSerializan() {
        String json = TramaCodec.aJson(Mensaje.de(TipoMensaje.LOGOUT));
        assertFalse(json.contains("contenido"));
        assertEquals("{\"tipo\":\"LOGOUT\"}", json);
    }

    @Test
    void streamIdaYVuelta() throws Exception {
        Mensaje original = Mensaje.builder()
                .tipo(TipoMensaje.MENSAJE_IMAGEN)
                .remitente("A001")
                .contenidoImagen("aG9sYQ==")
                .nombreArchivo("foto.png")
                .build();

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        TramaCodec.escribir(buffer, original);

        Mensaje recibido = TramaCodec.leer(new ByteArrayInputStream(buffer.toByteArray()));
        assertEquals(original, recibido);
    }

    @Test
    void rechazaTramaCorta() {
        assertThrows(CodecException.class, () -> TramaCodec.decodificar(new byte[]{1, 2}));
    }

    @Test
    void rechazaJsonSinTipo() {
        byte[] payload = "{\"contenido\":\"x\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] trama = new byte[4 + payload.length];
        trama[3] = (byte) payload.length;
        System.arraycopy(payload, 0, trama, 4, payload.length);
        assertThrows(CodecException.class, () -> TramaCodec.decodificar(trama));
    }

    @Test
    void rechazaMensajeSinTipoAlCodificar() {
        assertThrows(CodecException.class, () -> TramaCodec.codificar(Mensaje.builder().build()));
    }
}
