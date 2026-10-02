package universidad.mensajeria.common.codec;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import universidad.mensajeria.common.tipos.Mensaje;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Wire protocol lenguaje-neutro (PROTOCOLO.md):
 * TRAMA = [4 bytes longitud big-endian][payload UTF-8 JSON]
 * Implementacion Java; los demas lenguajes replican este formato exacto.
 */
public final class TramaCodec {

    /** Limite por trama para no agotar memoria (imagenes van en base64 dentro del JSON). */
    public static final int TAMANO_MAXIMO_TRAMA = 16 * 1024 * 1024;

    private static final Gson GSON = new GsonBuilder().create();

    private TramaCodec() {
    }

    public static byte[] codificar(Mensaje mensaje) {
        if (mensaje == null || mensaje.tipo() == null) {
            throw new CodecException("No se puede codificar un mensaje sin tipo");
        }
        byte[] payload = GSON.toJson(mensaje).getBytes(StandardCharsets.UTF_8);
        if (payload.length > TAMANO_MAXIMO_TRAMA) {
            throw new CodecException("Payload de " + payload.length + " bytes excede el maximo de "
                    + TAMANO_MAXIMO_TRAMA);
        }
        byte[] trama = new byte[4 + payload.length];
        trama[0] = (byte) (payload.length >>> 24);
        trama[1] = (byte) (payload.length >>> 16);
        trama[2] = (byte) (payload.length >>> 8);
        trama[3] = (byte) payload.length;
        System.arraycopy(payload, 0, trama, 4, payload.length);
        return trama;
    }

    public static Mensaje decodificar(byte[] trama) {
        if (trama == null || trama.length < 5) {
            throw new CodecException("Trama demasiado corta: " + (trama == null ? 0 : trama.length) + " bytes");
        }
        int longitud = ((trama[0] & 0xFF) << 24) | ((trama[1] & 0xFF) << 16)
                | ((trama[2] & 0xFF) << 8) | (trama[3] & 0xFF);
        if (longitud <= 0 || longitud > TAMANO_MAXIMO_TRAMA) {
            throw new CodecException("Longitud de payload invalida: " + longitud);
        }
        if (trama.length < 4 + longitud) {
            throw new CodecException("Trama incompleta: esperaba " + (4 + longitud) + " bytes, hay " + trama.length);
        }
        return aMensaje(new String(trama, 4, longitud, StandardCharsets.UTF_8));
    }

    /** Escribe una trama completa en el stream (bloqueante, sincronizar por socket en el llamador). */
    public static void escribir(OutputStream salida, Mensaje mensaje) throws IOException {
        salida.write(codificar(mensaje));
        salida.flush();
    }

    /** Lee exactamente una trama del stream; bloquea hasta recibirla completa. */
    public static Mensaje leer(InputStream entrada) throws IOException {
        DataInputStream datos = new DataInputStream(entrada);
        int longitud = datos.readInt();
        if (longitud <= 0 || longitud > TAMANO_MAXIMO_TRAMA) {
            throw new CodecException("Longitud de payload invalida: " + longitud);
        }
        byte[] payload = new byte[longitud];
        datos.readFully(payload);
        return aMensaje(new String(payload, StandardCharsets.UTF_8));
    }

    private static Mensaje aMensaje(String json) {
        try {
            Mensaje mensaje = GSON.fromJson(json, Mensaje.class);
            if (mensaje == null || mensaje.tipo() == null) {
                throw new CodecException("JSON sin campo 'tipo' obligatorio");
            }
            return mensaje;
        } catch (CodecException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CodecException("JSON invalido: " + e.getMessage(), e);
        }
    }

    /** Serializacion directa a JSON (para logs/diagnostico). */
    public static String aJson(Mensaje mensaje) {
        return GSON.toJson(mensaje);
    }
}
