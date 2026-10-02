package universidad.mensajeria.cliente.transversal.config;

import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Configuración del cliente (mismo formato client.properties en Java, Python
 * y C#: host, puerto, db.url, idioma — §5.2). Sin hardcode: todo sale de aquí.
 */
public record ClienteConfig(String host, int puerto, String dbUrl,
                            String dbUser, String dbPass, String idioma,
                            boolean tlsHabilitado, int tlsPuerto, String tlsConfianza) {

    public static ClienteConfig cargar() throws Exception {
        Properties props = new Properties();
        try (InputStream in = ClienteConfig.class.getResourceAsStream("/client.properties")) {
            if (in == null) {
                throw new IllegalStateException("Falta client.properties en el classpath");
            }
            props.load(in);
        }

        String rutaConfiguracion = System.getProperty("mensajeria.config");
        Path archivoExterno = rutaConfiguracion == null || rutaConfiguracion.isBlank()
                ? Path.of("client.properties")
                : Path.of(rutaConfiguracion);
        if (Files.exists(archivoExterno)) {
            try (Reader reader = Files.newBufferedReader(archivoExterno, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
        } else if (rutaConfiguracion != null && !rutaConfiguracion.isBlank()) {
            throw new IllegalStateException("No existe la configuración indicada: " + archivoExterno);
        }
        return desde(props);
    }

    /** Fábrica desde propiedades (tests y arranque). */
    public static ClienteConfig desde(Properties props) {
        String host = props.getProperty("host", "localhost");
        int puerto = Integer.parseInt(props.getProperty("puerto", "5000"));
        String dbUrl = props.getProperty("db.url", "jdbc:h2:~/mensajeria_cliente;AUTO_SERVER=TRUE");
        if (puerto <= 0 || puerto > 65535) {
            puerto = 5000;
        }
        int tlsPuerto;
        try {
            tlsPuerto = Integer.parseInt(props.getProperty("tls.puerto", "5001"));
        } catch (NumberFormatException e) {
            tlsPuerto = 5001;
        }
        if (tlsPuerto <= 0 || tlsPuerto > 65535) {
            tlsPuerto = 5001;
        }
        return new ClienteConfig(host, puerto, dbUrl,
                props.getProperty("db.user", "sa"),
                props.getProperty("db.pass", ""),
                props.getProperty("idioma", "es"),
                Boolean.parseBoolean(props.getProperty("tls.habilitado", "false")),
                tlsPuerto,
                props.getProperty("tls.confianza", ""));
    }

    /** URL H2 estable y compartida solo entre instancias de la misma cuenta. */
    public String dbUrlParaCuenta(String codigo) {
        if (codigo == null || !codigo.matches("[A-Za-z0-9_-]{1,40}")) {
            throw new IllegalArgumentException("codigo de cuenta invalido para la base local");
        }
        if (!dbUrl.startsWith("jdbc:h2:")) {
            throw new IllegalStateException("db.url debe usar H2 para aislar el historial por cuenta");
        }
        int opciones = dbUrl.indexOf(';');
        String base = opciones < 0 ? dbUrl : dbUrl.substring(0, opciones);
        String sufijo = opciones < 0 ? "" : dbUrl.substring(opciones);
        if (!base.toLowerCase().startsWith("jdbc:h2:mem:")) {
            if (sufijo.matches("(?is).*;AUTO_SERVER=(TRUE|FALSE)(;.*)?")) {
                sufijo = sufijo.replaceFirst("(?i)AUTO_SERVER=(TRUE|FALSE)", "AUTO_SERVER=TRUE");
            } else {
                sufijo += ";AUTO_SERVER=TRUE";
            }
        }
        return base + "_" + codigo + sufijo;
    }
}
