package universidad.mensajeria.cliente.componentes.historial;

import universidad.mensajeria.cliente.persistencia.LocalStore;
import universidad.mensajeria.cliente.transversal.records.MensajeLocal;
import universidad.mensajeria.cliente.transversal.records.PendienteEnvio;
import universidad.mensajeria.cliente.transversal.records.RecienteChat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * DAO H2 del historial (queries canónicas de HISTORIAL_LOCAL.md, tal cual:
 * upsert portable con DELETE + INSERT, sin sintaxis de motor).
 */
public final class HistorialLocal implements InterfazHistorialLocal {

    // HISTORIAL_LOCAL.md §1
    private static final String BORRAR_MENSAJE = "DELETE FROM historial_local WHERE id_mensaje = ?";
    private static final String INSERTAR_MENSAJE = """
            INSERT INTO historial_local (
                id_mensaje, origen, destino, tipo, contenido, hash_sha256,
                num_caracteres, num_palabras, nombre_archivo, ruta_archivo,
                tamano_archivo, fecha_envio, enviado, descargado, estado, archivo_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    // HISTORIAL_LOCAL.md §2 (conteo; la lista usa PAGINA_RESUMEN en 13.9)
    private static final String CONTAR_CONVERSACION = """
            SELECT COUNT(*)
            FROM historial_local
            WHERE (origen = ? AND destino = ?)
               OR (origen = ? AND destino = ?)""";

    // HISTORIAL_LOCAL.md §3
    private static final String MARCAR_DESCARGADO = """
            UPDATE historial_local
            SET descargado = 1, ruta_archivo = ?
            WHERE id_mensaje = ?""";
    private static final String MARCAR_ESTADO = """
            UPDATE historial_local
            SET estado = ?
            WHERE id_mensaje = ?
              AND CASE estado
                    WHEN 'LEIDO' THEN 3
                    WHEN 'ENTREGADO' THEN 2
                    WHEN 'ENVIADO' THEN 1
                    ELSE 0
                  END <= CASE ?
                    WHEN 'LEIDO' THEN 3
                    WHEN 'ENTREGADO' THEN 2
                    WHEN 'ENVIADO' THEN 1
                    ELSE 0
                  END""";
    private static final String MARCAR_LEIDOS = """
            UPDATE historial_local
            SET estado = 'LEIDO', descargado = 1
            WHERE ((origen = ? AND destino = ?) OR (origen = ? AND destino = ?))
              AND enviado = 0""";
    private static final String IDS_NO_LEIDOS = """
            SELECT id_mensaje FROM historial_local
            WHERE origen = ? AND destino = ? AND enviado = 0 AND estado <> 'LEIDO'
            ORDER BY fecha_envio ASC""";
    private static final String LISTAR_RECIENTES = """
            SELECT otro, MAX(fecha_envio) AS ultima
            FROM (
                SELECT CASE WHEN origen = ? THEN destino ELSE origen END AS otro,
                       fecha_envio
                FROM historial_local
                WHERE origen = ? OR destino = ?
            ) GROUP BY otro ORDER BY ultima DESC""";
    /**
     * FASE 13.4/13.8: preview sin tocar blobs. Ni siquiera SUBSTR sirve contra
     * imágenes: H2 lee el CLOB completo (MB) para recortar 160 chars. Con CASE
     * el `contenido` jamás se lee cuando el último es imagen.
     */
    private static final String ULTIMO_PREVIEW = """
            SELECT CASE WHEN tipo = 'MENSAJE_IMAGEN' THEN NULL
                        ELSE SUBSTR(contenido, 1, 160) END,
                   tipo, fecha_envio, nombre_archivo
            FROM historial_local
            WHERE (origen = ? AND destino = ?) OR (origen = ? AND destino = ?)
            ORDER BY fecha_envio DESC LIMIT 1""";
    /**
     * FASE 13.9/13.15: la conversación lista metadata + TEXTO completo, pero
     * NUNCA blobs de imagen (van por `blobPorId`). El NULL plano anterior
     * dejaba las burbujas de texto vacías (regresión 13.9, corregida aquí).
     */
    private static final String PAGINA_RESUMEN = """
            SELECT id_mensaje, origen, destino, tipo,
                   CASE WHEN tipo = 'MENSAJE_IMAGEN' THEN NULL ELSE contenido END,
                   hash_sha256,
                   num_caracteres, num_palabras, nombre_archivo, ruta_archivo,
                   tamano_archivo, fecha_envio, enviado, descargado, estado, archivo_id
            FROM historial_local
            WHERE (origen = ? AND destino = ?)
               OR (origen = ? AND destino = ?)
            ORDER BY fecha_envio DESC
            LIMIT ? OFFSET ?""";
    private static final String BLOB_POR_ID = """
            SELECT contenido FROM historial_local WHERE id_mensaje = ?""";
    private static final String ARCHIVO_ID_POR_ID = """
            SELECT archivo_id FROM historial_local WHERE id_mensaje = ?""";
    private static final String ACTUALIZAR_ARCHIVO_ID =
            "UPDATE historial_local SET archivo_id = ? WHERE id_mensaje = ?";
    // HISTORIAL_LOCAL.md §7
    private static final String ACTUALIZAR_CONTENIDO = """
            UPDATE historial_local SET contenido = ? WHERE id_mensaje = ?""";
    private static final String CONTAR_NO_LEIDOS = """
            SELECT COUNT(*) FROM historial_local
            WHERE origen = ? AND destino = ? AND enviado = 0 AND estado <> 'LEIDO'""";

    // HISTORIAL_LOCAL.md §4-5
    private static final String LISTAR_PENDIENTES = """
            SELECT id, tipo, origen, destino, contenido, nombre_archivo, payload,
                   fecha_creado, intentos, ultimo_error
            FROM pendientes_envio
            ORDER BY fecha_creado ASC""";
    private static final String BORRAR_PENDIENTE = "DELETE FROM pendientes_envio WHERE id = ?";
    private static final String INSERTAR_PENDIENTE = """
            INSERT INTO pendientes_envio (id, tipo, origen, destino, contenido,
                nombre_archivo, payload, fecha_creado, intentos, ultimo_error)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, NULL)""";
    private static final String INCREMENTAR_INTENTO = """
            UPDATE pendientes_envio
            SET intentos = intentos + 1, ultimo_error = ?
            WHERE id = ?""";
    private static final String PENDIENTE_POR_ID = """
            SELECT id, tipo, origen, destino, contenido, nombre_archivo, payload,
                   fecha_creado, intentos, ultimo_error
            FROM pendientes_envio
            WHERE id = ?""";

    private final LocalStore store;

    public HistorialLocal(LocalStore store) {
        this.store = store;
    }

    @Override
    public void prepararCuenta(String codigo) throws Exception {
        store.prepararCuenta(codigo);
    }

    @Override
    public void guardar(MensajeLocal mensaje) throws Exception {
        try (Connection conn = store.abrir();
             PreparedStatement borrar = conn.prepareStatement(BORRAR_MENSAJE);
             PreparedStatement insertar = conn.prepareStatement(INSERTAR_MENSAJE)) {
            borrar.setString(1, mensaje.idMensaje());
            borrar.executeUpdate();
            insertar.setString(1, mensaje.idMensaje());
            insertar.setString(2, mensaje.origen());
            insertar.setString(3, mensaje.destino());
            insertar.setString(4, mensaje.tipo());
            insertar.setString(5, mensaje.contenido());
            insertar.setString(6, mensaje.hashSha256());
            insertar.setObject(7, mensaje.numCaracteres());
            insertar.setObject(8, mensaje.numPalabras());
            insertar.setString(9, mensaje.nombreArchivo());
            insertar.setString(10, mensaje.rutaArchivo());
            insertar.setObject(11, mensaje.tamanoArchivo());
            insertar.setString(12, mensaje.fechaEnvio());
            insertar.setInt(13, mensaje.enviado() ? 1 : 0);
            insertar.setInt(14, mensaje.descargado() ? 1 : 0);
            insertar.setString(15, mensaje.estado() != null ? mensaje.estado() : "ENVIADO");
            insertar.setString(16, mensaje.archivoId());
            insertar.executeUpdate();
        }
    }

    @Override
    public List<MensajeLocal> pagina(String remitente, String destinatario,
                                     int limite, int offset) throws Exception {
        try (Connection conn = store.abrir();
              PreparedStatement q = conn.prepareStatement(PAGINA_RESUMEN)) {
            q.setString(1, remitente);
            q.setString(2, destinatario);
            q.setString(3, destinatario);
            q.setString(4, remitente);
            q.setInt(5, limite);
            q.setInt(6, offset);
            List<MensajeLocal> filas = new ArrayList<>();
            try (ResultSet rs = q.executeQuery()) {
                while (rs.next()) {
                    filas.add(leerMensaje(rs));
                }
            }
            return filas;
        }
    }

    @Override
    public Optional<String> blobPorId(String idMensaje) throws Exception {
        try (Connection conn = store.abrir();
              PreparedStatement q = conn.prepareStatement(BLOB_POR_ID)) {
            q.setString(1, idMensaje);
            try (ResultSet rs = q.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        }
    }

    @Override
    public Optional<String> archivoIdPorId(String idMensaje) throws Exception {
        try (Connection conn = store.abrir();
             PreparedStatement q = conn.prepareStatement(ARCHIVO_ID_POR_ID)) {
            q.setString(1, idMensaje);
            try (ResultSet rs = q.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        }
    }

    @Override
    public void actualizarArchivoId(String idMensaje, String archivoId) throws Exception {
        try (Connection conn = store.abrir();
             PreparedStatement q = conn.prepareStatement(ACTUALIZAR_ARCHIVO_ID)) {
            q.setString(1, archivoId);
            q.setString(2, idMensaje);
            q.executeUpdate();
        }
    }

    @Override
    public void actualizarContenido(String idMensaje, String base64) throws Exception {
        try (Connection conn = store.abrir();
              PreparedStatement q = conn.prepareStatement(ACTUALIZAR_CONTENIDO)) {
            q.setString(1, base64);
            q.setString(2, idMensaje);
            q.executeUpdate();
        }
    }

    @Override
    public int contar(String remitente, String destinatario) throws Exception {
        try (Connection conn = store.abrir();
             PreparedStatement q = conn.prepareStatement(CONTAR_CONVERSACION)) {
            q.setString(1, remitente);
            q.setString(2, destinatario);
            q.setString(3, destinatario);
            q.setString(4, remitente);
            try (ResultSet rs = q.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Override
    public void marcarDescargado(String idMensaje, String rutaArchivo) throws Exception {
        try (Connection conn = store.abrir();
             PreparedStatement q = conn.prepareStatement(MARCAR_DESCARGADO)) {
            q.setString(1, rutaArchivo);
            q.setString(2, idMensaje);
            q.executeUpdate();
        }
    }

    @Override
    public void marcarEstado(String idMensaje, String estado) throws Exception {
        try (Connection conn = store.abrir();
             PreparedStatement q = conn.prepareStatement(MARCAR_ESTADO)) {
            q.setString(1, estado);
            q.setString(2, idMensaje);
            q.setString(3, estado);
            q.executeUpdate();
        }
    }

    @Override
    public void marcarLeidosDe(String yo, String otro) throws Exception {
        try (Connection conn = store.abrir();
             PreparedStatement q = conn.prepareStatement(MARCAR_LEIDOS)) {
            q.setString(1, otro);
            q.setString(2, yo);
            q.setString(3, yo);
            q.setString(4, otro);
            q.executeUpdate();
        }
    }

    @Override
    public List<String> idsNoLeidos(String yo, String otro) throws Exception {
        try (Connection conn = store.abrir();
             PreparedStatement q = conn.prepareStatement(IDS_NO_LEIDOS)) {
            q.setString(1, otro);
            q.setString(2, yo);
            try (ResultSet rs = q.executeQuery()) {
                List<String> ids = new ArrayList<>();
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
                return ids;
            }
        }
    }

    @Override
    public List<RecienteChat> recientes(String yo) throws Exception {
        List<RecienteChat> salida = new ArrayList<>();
        try (Connection conn = store.abrir();
             PreparedStatement q = conn.prepareStatement(LISTAR_RECIENTES)) {
            q.setString(1, yo);
            q.setString(2, yo);
            q.setString(3, yo);
            List<String> otros = new ArrayList<>();
            try (ResultSet rs = q.executeQuery()) {
                while (rs.next()) {
                    otros.add(rs.getString(1));
                }
            }
            for (String otro : otros) {
                String contenido = "";
                String tipo = "";
                String fecha = "";
                try (PreparedStatement u = conn.prepareStatement(ULTIMO_PREVIEW)) {
                    u.setString(1, yo);
                    u.setString(2, otro);
                    u.setString(3, otro);
                    u.setString(4, yo);
                    try (ResultSet rs = u.executeQuery()) {
                        if (rs.next()) {
                            contenido = rs.getString(1) != null ? rs.getString(1) : "";
                            tipo = rs.getString(2) != null ? rs.getString(2) : "";
                            fecha = rs.getString(3) != null ? rs.getString(3) : "";
                            String nombre = rs.getString(4);
                            if ("MENSAJE_IMAGEN".equals(tipo)) {
                                contenido = nombre != null && !nombre.isEmpty()
                                        ? "[imagen] " + nombre : "[imagen]";
                            }
                        }
                    }
                }
                int noLeidos = 0;
                try (PreparedStatement c = conn.prepareStatement(CONTAR_NO_LEIDOS)) {
                    c.setString(1, otro);
                    c.setString(2, yo);
                    try (ResultSet rs = c.executeQuery()) {
                        if (rs.next()) {
                            noLeidos = rs.getInt(1);
                        }
                    }
                }
                salida.add(new RecienteChat(otro, contenido, tipo, fecha, noLeidos, false));
            }
        }
        return salida;
    }

    @Override
    public List<PendienteEnvio> pendientes() throws Exception {
        try (Connection conn = store.abrir();
             PreparedStatement q = conn.prepareStatement(LISTAR_PENDIENTES);
             ResultSet rs = q.executeQuery()) {
            List<PendienteEnvio> filas = new ArrayList<>();
            while (rs.next()) {
                filas.add(leerPendiente(rs));
            }
            return filas;
        }
    }

    @Override
    public void registrarPendiente(PendienteEnvio pendiente) throws Exception {
        try (Connection conn = store.abrir();
             PreparedStatement borrar = conn.prepareStatement(BORRAR_PENDIENTE);
             PreparedStatement insertar = conn.prepareStatement(INSERTAR_PENDIENTE)) {
            borrar.setString(1, pendiente.id());
            borrar.executeUpdate();
            insertar.setString(1, pendiente.id());
            insertar.setString(2, pendiente.tipo());
            insertar.setString(3, pendiente.origen());
            insertar.setString(4, pendiente.destino());
            insertar.setString(5, pendiente.contenido());
            insertar.setString(6, pendiente.nombreArchivo());
            insertar.setBytes(7, pendiente.payload());
            insertar.setString(8, pendiente.fechaCreado());
            insertar.executeUpdate();
        }
    }

    @Override
    public void incrementarIntento(String id, String ultimoError) throws Exception {
        try (Connection conn = store.abrir();
             PreparedStatement q = conn.prepareStatement(INCREMENTAR_INTENTO)) {
            q.setString(1, ultimoError);
            q.setString(2, id);
            q.executeUpdate();
        }
    }

    @Override
    public void eliminarPendiente(String id) throws Exception {
        try (Connection conn = store.abrir();
             PreparedStatement q = conn.prepareStatement(BORRAR_PENDIENTE)) {
            q.setString(1, id);
            q.executeUpdate();
        }
    }

    @Override
    public Optional<PendienteEnvio> pendientePorId(String id) throws Exception {
        try (Connection conn = store.abrir();
             PreparedStatement q = conn.prepareStatement(PENDIENTE_POR_ID)) {
            q.setString(1, id);
            try (ResultSet rs = q.executeQuery()) {
                return rs.next() ? Optional.of(leerPendiente(rs)) : Optional.empty();
            }
        }
    }

    private static MensajeLocal leerMensaje(ResultSet rs) throws Exception {
        String estado;
        try {
            estado = rs.getString(15);
        } catch (Exception e) {
            estado = "ENVIADO";
        }
        return new MensajeLocal(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6),
                aInteger(rs.getObject(7)), aInteger(rs.getObject(8)),
                rs.getString(9), rs.getString(10),
                aLong(rs.getObject(11)), rs.getString(12),
                rs.getInt(13) == 1, rs.getInt(14) == 1,
                estado != null ? estado : "ENVIADO", rs.getString(16));
    }

    /** H2 devuelve INTEGER como Integer y SQLite como Long: normalizar por Number. */
    private static Integer aInteger(Object valor) {
        return valor == null ? null : ((Number) valor).intValue();
    }

    private static Long aLong(Object valor) {
        return valor == null ? null : ((Number) valor).longValue();
    }

    private static PendienteEnvio leerPendiente(ResultSet rs) throws Exception {
        return new PendienteEnvio(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getBytes(7),
                rs.getString(8), rs.getInt(9), rs.getString(10));
    }
}
