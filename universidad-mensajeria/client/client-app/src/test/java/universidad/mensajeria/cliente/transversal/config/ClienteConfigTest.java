package universidad.mensajeria.cliente.transversal.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** client.properties: mismo formato que Python y C# (§5.2). */
class ClienteConfigTest {

    @Test
    void valoresPorDefectoSinProperties() {
        ClienteConfig cfg = ClienteConfig.desde(new Properties());

        assertEquals("localhost", cfg.host());
        assertEquals(5000, cfg.puerto());
        assertEquals("jdbc:h2:~/mensajeria_cliente;AUTO_SERVER=TRUE", cfg.dbUrl());
        assertEquals("es", cfg.idioma());
        assertEquals(false, cfg.tlsHabilitado());
        assertEquals(5001, cfg.tlsPuerto());
    }

    @Test
    void leeOpcionesTls() {
        Properties props = new Properties();
        props.setProperty("tls.habilitado", "true");
        props.setProperty("tls.puerto", "5443");
        props.setProperty("tls.confianza", "/tmp/confianza.jks");

        ClienteConfig cfg = ClienteConfig.desde(props);

        assertEquals(true, cfg.tlsHabilitado());
        assertEquals(5443, cfg.tlsPuerto());
        assertEquals("/tmp/confianza.jks", cfg.tlsConfianza());
    }

    @Test
    void leeFormatoCompartidoYPuertoInvalidoCaeADefecto() {
        Properties props = new Properties();
        props.setProperty("host", "192.168.1.10");
        props.setProperty("puerto", "99999");
        props.setProperty("db.url", "jdbc:h2:mem:x");
        props.setProperty("idioma", "es");

        ClienteConfig cfg = ClienteConfig.desde(props);

        assertEquals("192.168.1.10", cfg.host());
        assertEquals(5000, cfg.puerto());
        assertEquals("jdbc:h2:mem:x", cfg.dbUrl());
    }

    @Test
    void permiteSobrescribirLaConfiguracionDelJarConArchivoExterno(@TempDir Path temp) throws Exception {
        Path archivo = temp.resolve("client.properties");
        Files.writeString(archivo, "host=10.0.0.25\npuerto=5012\n");
        String previa = System.getProperty("mensajeria.config");
        try {
            System.setProperty("mensajeria.config", archivo.toString());
            ClienteConfig cfg = ClienteConfig.cargar();
            assertEquals("10.0.0.25", cfg.host());
            assertEquals(5012, cfg.puerto());
        } finally {
            if (previa == null) System.clearProperty("mensajeria.config");
            else System.setProperty("mensajeria.config", previa);
        }
    }

    @Test
    void generaUrlEstablePorCuentaYConservaOpcionesH2() {
        Properties props = new Properties();
        props.setProperty("db.url", "jdbc:h2:~/mensajeria_cliente;AUTO_SERVER=TRUE");
        ClienteConfig cfg = ClienteConfig.desde(props);

        assertEquals("jdbc:h2:~/mensajeria_cliente_A001234567;AUTO_SERVER=TRUE",
                cfg.dbUrlParaCuenta("A001234567"));
        assertThrows(IllegalArgumentException.class, () -> cfg.dbUrlParaCuenta("../otra"));

        props.setProperty("db.url", "jdbc:h2:file:C:/temp/chat;AUTO_SERVER=FALSE");
        assertEquals("jdbc:h2:file:C:/temp/chat_A001;AUTO_SERVER=TRUE",
                ClienteConfig.desde(props).dbUrlParaCuenta("A001"));
    }
}
