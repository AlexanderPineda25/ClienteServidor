package universidad.mensajeria.cliente.persistencia;

import universidad.mensajeria.cliente.transversal.config.ClienteConfig;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.h2.jdbcx.JdbcConnectionPool;

/**
 * Acceso H2 local (§7.1 persistencia): abre conexiones contra db.url y crea el
 * esquema ejecutando common-protocol/schema-local.sql al arrancar (sin Flyway
 * en cliente, §5.2). Accesible SOLO vía los DAOs de componentes (convención
 * de capas §11: la UI y la fachada nunca lo tocan directo).
 */
public final class LocalStore {

    private final ClienteConfig config;
    private String urlActual;
    private boolean esquemaLegadoListo;

    /** FASE 13.4: pool H2 reutilizable (cero deps nuevas: viene en el driver). */
    private JdbcConnectionPool pool;

    public LocalStore(ClienteConfig config) {
        this.config = config;
        this.urlActual = config.dbUrl();
    }

    /** Crea las tablas del contrato multilenguaje si no existen. */
    public int inicializar() throws Exception {
        String sql;
        try (InputStream in = LocalStore.class.getResourceAsStream("/schema-local.sql")) {
            if (in == null) {
                throw new IllegalStateException(
                        "Falta schema-local.sql (common-protocol) en el classpath");
            }
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int ejecutadas = 0;
        try (Connection conn = abrir(); Statement st = conn.createStatement()) {
            for (String sentencia : sql.split(";")) {
                String limpia = sinComentarios(sentencia);
                if (limpia.isEmpty()) {
                    continue;
                }
                st.execute(limpia);
                ejecutadas++;
            }
            // Migración suave para bases H2 ya creadas sin la columna estado.
            try {
                st.execute("ALTER TABLE historial_local ADD COLUMN IF NOT EXISTS estado"
                        + " TEXT NOT NULL DEFAULT 'ENVIADO'");
                ejecutadas++;
            } catch (Exception ignorada) {
                // H2 viejo sin IF NOT EXISTS: intentar sin cláusula
                try {
                    st.execute("ALTER TABLE historial_local ADD COLUMN estado"
                            + " TEXT NOT NULL DEFAULT 'ENVIADO'");
                    ejecutadas++;
                } catch (Exception duplicada) {
                    // ya existe
                }
            }
            try {
                st.execute("ALTER TABLE historial_local ADD COLUMN IF NOT EXISTS archivo_id TEXT");
                ejecutadas++;
            } catch (Exception ignorada) {
                try {
                    st.execute("ALTER TABLE historial_local ADD COLUMN archivo_id TEXT");
                    ejecutadas++;
                } catch (Exception duplicada) {
                    // ya existe
                }
            }
        }
        if (urlActual.equals(config.dbUrl())) {
            esquemaLegadoListo = true;
        }
        return ejecutadas;
    }

    /**
     * Abre el archivo estable de la cuenta y copia una sola vez su historial
     * desde el H2 compartido anterior. El archivo legado nunca se modifica ni
     * se elimina durante la copia.
     */
    public synchronized void prepararCuenta(String codigo) throws Exception {
        if (!esquemaLegadoListo) {
            seleccionarUrl(config.dbUrl());
            inicializar();
        }
        seleccionarUrl(config.dbUrlParaCuenta(codigo));
        inicializar();
        importarHistorialLegado(codigo);
    }

    /** Conexión del pool (thread-safe; close() la devuelve, no la destruye). */
    public synchronized Connection abrir() throws Exception {
        if (pool == null) {
            pool = JdbcConnectionPool.create(
                    urlActual, config.dbUser(), config.dbPass());
            pool.setMaxConnections(4);
        }
        return pool.getConnection();
    }

    private void seleccionarUrl(String url) {
        if (urlActual.equals(url)) {
            return;
        }
        cerrar();
        urlActual = url;
    }

    private void importarHistorialLegado(String codigo) throws Exception {
        try (Connection destino = abrir();
             Connection legado = DriverManager.getConnection(
                     config.dbUrl(), config.dbUser(), config.dbPass())) {
            try (Statement ddl = destino.createStatement()) {
                ddl.execute("CREATE TABLE IF NOT EXISTS cliente_migracion_lock (id INTEGER PRIMARY KEY)");
                ddl.execute("CREATE TABLE IF NOT EXISTS cliente_migraciones (version TEXT PRIMARY KEY)");
            }
            try (PreparedStatement lock = destino.prepareStatement(
                    "INSERT INTO cliente_migracion_lock (id) VALUES (1)")) {
                lock.executeUpdate();
            } catch (SQLException duplicada) {
                if (duplicada.getErrorCode() != 23505) {
                    throw duplicada;
                }
            }

            destino.setAutoCommit(false);
            try {
                try (Statement lock = destino.createStatement();
                     ResultSet fila = lock.executeQuery(
                             "SELECT id FROM cliente_migracion_lock WHERE id = 1 FOR UPDATE")) {
                    if (!fila.next()) {
                        throw new SQLException("no se pudo bloquear la migracion local");
                    }
                }
                if (migracionHecha(destino)) {
                    destino.commit();
                    return;
                }
                copiarHistorial(legado, destino, codigo);
                copiarPendientes(legado, destino, codigo);
                try (PreparedStatement marcar = destino.prepareStatement(
                        "INSERT INTO cliente_migraciones (version) VALUES (?)")) {
                    marcar.setString(1, "h2-compartido-a-cuenta-v1");
                    marcar.executeUpdate();
                }
                destino.commit();
            } catch (Exception e) {
                destino.rollback();
                throw e;
            } finally {
                destino.setAutoCommit(true);
            }
        }
    }

    private static boolean migracionHecha(Connection destino) throws SQLException {
        try (PreparedStatement q = destino.prepareStatement(
                "SELECT 1 FROM cliente_migraciones WHERE version = ?")) {
            q.setString(1, "h2-compartido-a-cuenta-v1");
            try (ResultSet fila = q.executeQuery()) {
                return fila.next();
            }
        }
    }

    private static void copiarHistorial(Connection legado, Connection destino, String codigo)
            throws SQLException {
        String consulta = """
                SELECT id_mensaje, origen, destino, tipo, contenido, hash_sha256,
                       num_caracteres, num_palabras, nombre_archivo, ruta_archivo,
                       tamano_archivo, fecha_envio, descargado, estado, archivo_id
                FROM historial_local WHERE origen = ? OR destino = ?""";
        String upsert = """
                MERGE INTO historial_local (id_mensaje, origen, destino, tipo, contenido,
                    hash_sha256, num_caracteres, num_palabras, nombre_archivo, ruta_archivo,
                    tamano_archivo, fecha_envio, enviado, descargado, estado, archivo_id)
                KEY (id_mensaje) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
        try (PreparedStatement q = legado.prepareStatement(consulta);
             PreparedStatement guardar = destino.prepareStatement(upsert)) {
            q.setString(1, codigo);
            q.setString(2, codigo);
            try (ResultSet filas = q.executeQuery()) {
                while (filas.next()) {
                    String origen = filas.getString("origen");
                    guardar.setString(1, filas.getString("id_mensaje"));
                    guardar.setString(2, origen);
                    guardar.setString(3, filas.getString("destino"));
                    guardar.setString(4, filas.getString("tipo"));
                    guardar.setString(5, filas.getString("contenido"));
                    guardar.setString(6, filas.getString("hash_sha256"));
                    guardar.setObject(7, filas.getObject("num_caracteres"));
                    guardar.setObject(8, filas.getObject("num_palabras"));
                    guardar.setString(9, filas.getString("nombre_archivo"));
                    guardar.setString(10, filas.getString("ruta_archivo"));
                    guardar.setObject(11, filas.getObject("tamano_archivo"));
                    guardar.setString(12, filas.getString("fecha_envio"));
                    guardar.setInt(13, codigo.equals(origen) ? 1 : 0);
                    guardar.setInt(14, filas.getInt("descargado"));
                    guardar.setString(15, filas.getString("estado"));
                    guardar.setString(16, filas.getString("archivo_id"));
                    guardar.addBatch();
                }
            }
            guardar.executeBatch();
        }
    }

    private static void copiarPendientes(Connection legado, Connection destino, String codigo)
            throws SQLException {
        String consulta = """
                SELECT id, tipo, origen, destino, contenido, nombre_archivo, payload,
                       fecha_creado, intentos, ultimo_error
                FROM pendientes_envio WHERE origen = ?""";
        String upsert = """
                MERGE INTO pendientes_envio (id, tipo, origen, destino, contenido,
                    nombre_archivo, payload, fecha_creado, intentos, ultimo_error)
                KEY (id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
        try (PreparedStatement q = legado.prepareStatement(consulta);
             PreparedStatement guardar = destino.prepareStatement(upsert)) {
            q.setString(1, codigo);
            try (ResultSet filas = q.executeQuery()) {
                while (filas.next()) {
                    for (int i = 1; i <= 10; i++) {
                        guardar.setObject(i, filas.getObject(i));
                    }
                    guardar.addBatch();
                }
            }
            guardar.executeBatch();
        }
    }

    /** Libera el pool (al salir de la app). */
    public synchronized void cerrar() {
        if (pool != null) {
            pool.dispose();
            pool = null;
        }
    }

    private static String sinComentarios(String sentencia) {
        StringBuilder sb = new StringBuilder();
        for (String linea : sentencia.split("\n")) {
            if (linea.trim().startsWith("--")) {
                continue;
            }
            sb.append(linea).append('\n');
        }
        return sb.toString().trim();
    }
}
