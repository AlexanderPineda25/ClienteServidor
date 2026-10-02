package universidad.mensajeria.cliente.componentes.cache;

import universidad.mensajeria.cliente.persistencia.LocalStore;
import universidad.mensajeria.cliente.transversal.records.UsuarioLocal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** DAO H2 de cache_usuarios (queries canónicas HISTORIAL_LOCAL.md §6-7). */
public final class CacheUsuarios implements InterfazCacheUsuarios {

    // HISTORIAL_LOCAL.md §6
    private static final String BORRAR_USUARIO = "DELETE FROM cache_usuarios WHERE codigo = ?";
    private static final String INSERTAR_USUARIO = """
            INSERT INTO cache_usuarios (codigo, nombres, apellidos, programa,
                conectado, fecha_registro, actualizado)
            VALUES (?, ?, ?, ?, ?, ?, ?)""";

    // HISTORIAL_LOCAL.md §7
    private static final String LISTAR_CACHE = """
            SELECT codigo, nombres, apellidos, programa, conectado, fecha_registro, actualizado
            FROM cache_usuarios
            ORDER BY conectado DESC, apellidos ASC""";
    private static final String POR_CODIGO = """
            SELECT codigo, nombres, apellidos, programa, conectado, fecha_registro, actualizado
            FROM cache_usuarios
            WHERE codigo = ?""";
    private static final String MARCAR_CONECTADO = """
            UPDATE cache_usuarios SET conectado = 1 WHERE codigo = ?""";
    private static final String MARCAR_TODOS_DESCONECTADOS = """
            UPDATE cache_usuarios SET conectado = 0""";
    private static final String BORRAR_DIRECTORIO = "DELETE FROM cache_usuarios";

    private final LocalStore store;

    public CacheUsuarios(LocalStore store) {
        this.store = store;
    }

    @Override
    public void guardar(UsuarioLocal usuario) throws Exception {
        try (Connection conn = store.abrir();
             PreparedStatement borrar = conn.prepareStatement(BORRAR_USUARIO);
             PreparedStatement insertar = conn.prepareStatement(INSERTAR_USUARIO)) {
            borrar.setString(1, usuario.codigo());
            borrar.executeUpdate();
            insertar.setString(1, usuario.codigo());
            insertar.setString(2, usuario.nombres());
            insertar.setString(3, usuario.apellidos());
            insertar.setString(4, usuario.programa());
            insertar.setInt(5, usuario.conectado() ? 1 : 0);
            insertar.setString(6, usuario.fechaRegistro());
            insertar.setString(7, usuario.actualizado());
            insertar.executeUpdate();
        }
    }

    @Override
    public void reemplazarDirectorio(List<UsuarioLocal> usuarios) throws Exception {
        try (Connection conn = store.abrir();
             PreparedStatement borrar = conn.prepareStatement(BORRAR_DIRECTORIO);
             PreparedStatement insertar = conn.prepareStatement(INSERTAR_USUARIO)) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                borrar.executeUpdate();
                for (UsuarioLocal usuario : usuarios) {
                    insertar.setString(1, usuario.codigo());
                    insertar.setString(2, usuario.nombres());
                    insertar.setString(3, usuario.apellidos());
                    insertar.setString(4, usuario.programa());
                    insertar.setInt(5, usuario.conectado() ? 1 : 0);
                    insertar.setString(6, usuario.fechaRegistro());
                    insertar.setString(7, usuario.actualizado());
                    insertar.addBatch();
                }
                insertar.executeBatch();
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        }
    }

    @Override
    public List<UsuarioLocal> listar() throws Exception {
        try (Connection conn = store.abrir();
             PreparedStatement q = conn.prepareStatement(LISTAR_CACHE);
             ResultSet rs = q.executeQuery()) {
            List<UsuarioLocal> filas = new ArrayList<>();
            while (rs.next()) {
                filas.add(leer(rs));
            }
            return filas;
        }
    }

    @Override
    public Optional<UsuarioLocal> porCodigo(String codigo) throws Exception {
        try (Connection conn = store.abrir();
             PreparedStatement q = conn.prepareStatement(POR_CODIGO)) {
            q.setString(1, codigo);
            try (ResultSet rs = q.executeQuery()) {
                return rs.next() ? Optional.of(leer(rs)) : Optional.empty();
            }
        }
    }

    @Override
    public void marcarConectados(List<String> codigos) throws Exception {
        try (Connection conn = store.abrir();
             PreparedStatement todos = conn.prepareStatement(MARCAR_TODOS_DESCONECTADOS);
             PreparedStatement uno = conn.prepareStatement(MARCAR_CONECTADO)) {
            todos.executeUpdate();
            for (String codigo : codigos) {
                uno.setString(1, codigo);
                uno.executeUpdate();
            }
        }
    }

    private static UsuarioLocal leer(ResultSet rs) throws Exception {
        return new UsuarioLocal(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getInt(5) == 1, rs.getString(6), rs.getString(7));
    }
}
