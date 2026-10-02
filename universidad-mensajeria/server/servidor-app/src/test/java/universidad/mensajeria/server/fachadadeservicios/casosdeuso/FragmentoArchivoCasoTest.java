package universidad.mensajeria.server.fachadadeservicios.casosdeuso;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import universidad.mensajeria.colas.InterfazGestorMensajes;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.interno.LimitesDTO;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.common.tipos.TipoMensaje;
import universidad.mensajeria.protocolo.InterfazProtocoloComunicacion;
import universidad.mensajeria.servicios.impl.Servicios;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** Reensamblado ARCHIVO_INICIO/PARTE/FIN con punta a punta al pipeline existente. */
class FragmentoArchivoCasoTest {

    @TempDir
    Path base;

    private final List<Mensaje> encolados = new ArrayList<>();
    private final List<Mensaje> respuestas = new ArrayList<>();

    private FragmentoArchivoCaso caso(long maximo) throws Exception {
        InterfazGestorMensajes colas = mock(InterfazGestorMensajes.class);
        doAnswer(inv -> {
            encolados.add(inv.getArgument(0));
            return null;
        }).when(colas).encolar(any());
        InterfazProtocoloComunicacion protocolo = mock(InterfazProtocoloComunicacion.class);
        doAnswer(inv -> {
            respuestas.add(inv.getArgument(1));
            return null;
        }).when(protocolo).enviar(any(), any());
        EncolarCaso encolar = new EncolarCaso(colas);
        return new FragmentoArchivoCaso(new Servicios(), new LimitesDTO(100, 3, maximo),
                base, encolar, new ResponderCaso(protocolo),
                mock(universidad.mensajeria.server.logdeeventos.InterfazLogDeEventos.class));
    }

    private static IdSesion sesion(String codigo) {
        return new IdSesion("s-" + codigo, codigo, "127.0.0.1");
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    @Test
    void reensamblaDosPartesYEncolaConElMismoId() throws Exception {
        FragmentoArchivoCaso caso = caso(52_428_800L);
        byte[] parte0 = new byte[FragmentoArchivoCaso.PARTE_BYTES];
        byte[] parte1 = "mundo".getBytes(StandardCharsets.UTF_8);
        byte[] todo = new byte[parte0.length + parte1.length];
        System.arraycopy(parte0, 0, todo, 0, parte0.length);
        System.arraycopy(parte1, 0, todo, parte0.length, parte1.length);
        IdSesion yo = sesion("A001");

        caso.iniciar(yo, "B002", "doc.txt", "text/plain", todo.length, 2, "t-1", "127.0.0.1");
        caso.parte(yo, "t-1", 0, Base64.getEncoder().encodeToString(parte0), "t-1-p0", "127.0.0.1");
        caso.parte(yo, "t-1", 1, Base64.getEncoder().encodeToString(parte1), "t-1-p1", "127.0.0.1");
        caso.finalizar(yo, "t-1", sha256(todo), "t-1-fin", "127.0.0.1");

        assertEquals(1, encolados.size());
        assertEquals(TipoMensaje.MENSAJE_ARCHIVO, encolados.get(0).tipo());
        assertEquals("t-1", encolados.get(0).id());
        assertEquals(0, caso.transferenciasActivas());
        assertTrue(respuestas.stream().allMatch(m -> m.tipo() == TipoMensaje.ACK));
    }

    @Test
    void rechazaTamanoSobreElTope() throws Exception {
        FragmentoArchivoCaso caso = caso(10L);
        caso.iniciar(sesion("A001"), "B002", "doc.bin", "application/octet-stream",
                11L, 1, "t-2", "127.0.0.1");
        assertTrue(respuestas.stream().anyMatch(m -> m.tipo() == TipoMensaje.ERROR));
        assertTrue(encolados.isEmpty());
    }

    @Test
    void hashDistintoNoEncola() throws Exception {
        FragmentoArchivoCaso caso = caso(52_428_800L);
        IdSesion yo = sesion("A001");
        caso.iniciar(yo, "B002", "doc.txt", "text/plain", 3, 1, "t-3", "127.0.0.1");
        caso.parte(yo, "t-3", 0,
                Base64.getEncoder().encodeToString("abc".getBytes(StandardCharsets.UTF_8)),
                "t-3-p0", "127.0.0.1");
        caso.finalizar(yo, "t-3", "hash-falso", "t-3-fin", "127.0.0.1");
        assertTrue(encolados.isEmpty());
        assertTrue(respuestas.stream().anyMatch(m -> m.tipo() == TipoMensaje.ERROR));
    }
}
