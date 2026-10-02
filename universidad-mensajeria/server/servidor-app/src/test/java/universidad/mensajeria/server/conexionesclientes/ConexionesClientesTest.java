package universidad.mensajeria.server.conexionesclientes;

import org.junit.jupiter.api.Test;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.server.conexionesclientes.ConexionesClientes;
import universidad.mensajeria.server.conexionesclientes.InterfazConexionesClientes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mapa de IdSesion por codigo: multi-conexion sin sockets ni salientes (§5.3). */
class ConexionesClientesTest {

    private final InterfazConexionesClientes conexiones = new ConexionesClientes();

    private static IdSesion sesion(String codigo) {
        return new IdSesion("s-" + codigo + "-" + System.nanoTime(), codigo, "127.0.0.1");
    }

    @Test
    void registraVariasSesionesPorCodigo() {
        IdSesion a1 = sesion("A001");
        IdSesion a2 = sesion("A001");

        conexiones.registrar("A001", a1);
        conexiones.registrar("A001", a2);
        conexiones.registrar("B002", sesion("B002"));

        assertEquals(java.util.List.of("A001", "B002"), conexiones.codigosConectados());
        assertEquals(2, conexiones.cantidadUsuariosConectados());
        assertEquals(3, conexiones.totalSesiones());
        assertEquals(2, conexiones.sesionesDe("A001").size());
    }

    @Test
    void removerIndicaSiSeguiaRegistrada() {
        IdSesion unica = sesion("A001");
        conexiones.registrar("A001", unica);

        assertTrue(conexiones.remover("A001", unica));
        assertFalse(conexiones.remover("A001", unica));
        assertTrue(conexiones.codigosConectados().isEmpty());
    }

    @Test
    void limpiarDejaElMapaVacio() {
        conexiones.registrar("A001", sesion("A001"));

        conexiones.limpiar();

        assertEquals(0, conexiones.totalSesiones());
    }
}
