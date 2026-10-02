package universidad.mensajeria.colas;

import org.junit.jupiter.api.Test;
import universidad.mensajeria.colas.impl.GestorColasDeMensajes;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.common.tipos.TipoMensaje;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cola + indice + consumidor + conversacion paginada (sin Spring). */
class GestorColasTest {

    private static Mensaje texto(String remitente, String destinatario, String contenido) {
        return Mensaje.builder().tipo(TipoMensaje.MENSAJE_TEXTO)
                .id(UUID.randomUUID().toString()).remitente(remitente)
                .destinatario(destinatario).contenido(contenido).build();
    }

    @Test
    void encolarIndexaNotificaYPagina() {
        InterfazGestorMensajes colas = new GestorColasDeMensajes();
        List<Mensaje> consumidos = new ArrayList<>();
        colas.registrarConsumidor(consumidos::add);

        colas.encolar(texto("A", "B", "uno"));
        colas.encolar(texto("B", "A", "dos"));
        colas.encolar(texto("A", "C", "tres"));

        assertEquals(3, colas.tamano());
        assertEquals(3, consumidos.size());
        assertTrue(colas.buscarPorId(consumidos.get(0).id()).isPresent());
        assertTrue(colas.buscarPorId("inexistente").isEmpty());

        List<Mensaje> pagina0 = colas.conversacion("A", "B", 0, 1);
        assertEquals(1, pagina0.size());
        assertEquals("uno", pagina0.get(0).contenido());
        assertEquals(1, colas.conversacion("A", "B", 1, 1).size());
        assertTrue(colas.conversacion("A", "B", 5, 10).isEmpty());

        colas.limpiar();
        assertEquals(0, colas.tamano());
    }

    @Test
    void sinIdGeneraUuid() {
        InterfazGestorMensajes colas = new GestorColasDeMensajes();
        List<Mensaje> consumidos = new ArrayList<>();
        colas.registrarConsumidor(consumidos::add);
        colas.encolar(Mensaje.builder().tipo(TipoMensaje.ACK).build());

        assertEquals(1, colas.tamano());
        assertEquals(1, consumidos.size());
        assertTrue(consumidos.get(0).id() != null && !consumidos.get(0).id().isBlank());
        assertTrue(colas.buscarPorId(consumidos.get(0).id()).isPresent());
    }
}
