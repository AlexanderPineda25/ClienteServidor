package universidad.mensajeria.server.fachadadeservicios.casosdeuso;

import org.junit.jupiter.api.Test;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.protocolo.InterfazProtocoloComunicacion;
import universidad.mensajeria.server.conexionesclientes.ConexionesClientes;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** Cierre administrativo de cualquier codigo, sesion o "*". */
class CerrarAdminCasoTest {

    private final List<String> cerradas = new ArrayList<>();
    private final ConexionesClientes conexiones = new ConexionesClientes();

    private CerrarAdminCaso caso() throws Exception {
        InterfazProtocoloComunicacion protocolo = mock(InterfazProtocoloComunicacion.class);
        doAnswer(inv -> {
            cerradas.add(((IdSesion) inv.getArgument(0)).id());
            return null;
        }).when(protocolo).cerrarSesion(any(), anyString());
        return new CerrarAdminCaso(protocolo, conexiones,
                mock(universidad.mensajeria.server.logdeeventos.InterfazLogDeEventos.class));
    }

    private void sembrar() {
        conexiones.registrar("A001", new IdSesion("s-a1", "A001", "127.0.0.1"));
        conexiones.registrar("A001", new IdSesion("s-a2", "A001", "127.0.0.1"));
        conexiones.registrar("B002", new IdSesion("s-b1", "B002", "127.0.0.1"));
    }

    @Test
    void cierraTodoUnCodigo() throws Exception {
        sembrar();
        assertEquals(2, caso().cerrar("A001", "prueba"));
        assertTrue(cerradas.contains("s-a1") && cerradas.contains("s-a2"));
        assertEquals(1, conexiones.totalSesiones());
    }

    @Test
    void cierraUnaSesionPorId() throws Exception {
        sembrar();
        assertEquals(1, caso().cerrar("s-b1", "prueba"));
        assertEquals(2, conexiones.totalSesiones());
    }

    @Test
    void asteriscoCierraTodo() throws Exception {
        sembrar();
        assertEquals(3, caso().cerrar("*", "prueba"));
        assertEquals(0, conexiones.totalSesiones());
    }

    @Test
    void sinBlancosLanzaError() throws Exception {
        sembrar();
        assertThrows(IllegalArgumentException.class, () -> caso().cerrar("ZZZ", "prueba"));
    }
}
