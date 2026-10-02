package universidad.mensajeria.server.transversal.config;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Fase 10.1 (P3): inactividad por defecto = 600 s = 10 minutos. */
class SesionPropertiesTest {

    @Test
    void porDefectoSonDiezMinutos() {
        assertEquals(600, new SesionProperties(600).timeoutSegundos());
    }

    @Test
    void valoresNoValidosCaenEnDiezMinutos() {
        assertEquals(600, new SesionProperties(0).timeoutSegundos());
        assertEquals(600, new SesionProperties(-1).timeoutSegundos());
    }

    @Test
    void serverPropertiesFijaDiezMinutos() throws Exception {
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/server.properties")) {
            props.load(in);
        }
        assertEquals("600", props.getProperty("red.sesion.timeout-segundos"));
    }
}
