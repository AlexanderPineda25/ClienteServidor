package universidad.mensajeria.cliente.componentes.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import universidad.mensajeria.cliente.persistencia.LocalStore;
import universidad.mensajeria.cliente.transversal.config.ClienteConfig;
import universidad.mensajeria.cliente.transversal.records.UsuarioLocal;

import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Caché de usuarios: directorio offline (HISTORIAL_LOCAL.md §6-7). */
class CacheUsuariosTest {

    private CacheUsuarios cache;

    @BeforeEach
    void montar() throws Exception {
        Properties props = new Properties();
        props.setProperty("db.url", "jdbc:h2:mem:cache" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1");
        LocalStore store = new LocalStore(ClienteConfig.desde(props));
        store.inicializar();
        cache = new CacheUsuarios(store);
    }

    private static UsuarioLocal usuario(String codigo, boolean conectado) {
        return new UsuarioLocal(codigo, "Nom " + codigo, "Ape", "Prog",
                conectado, "2026-01-01", "2026-01-02T10:00:00");
    }

    @Test
    void listarPoneConectadosPrimero() throws Exception {
        cache.guardar(usuario("B", false));
        cache.guardar(usuario("A", true));

        List<UsuarioLocal> filas = cache.listar();

        assertEquals(2, filas.size());
        assertEquals("A", filas.get(0).codigo());
        assertTrue(cache.porCodigo("B").isPresent());
        assertTrue(cache.porCodigo("ZZZ").isEmpty());
    }

    @Test
    void marcarConectadosActualizaBanderas() throws Exception {
        cache.guardar(usuario("A", true));
        cache.guardar(usuario("B", true));

        cache.marcarConectados(List.of("B"));

        List<UsuarioLocal> filas = cache.listar();
        assertEquals("B", filas.get(0).codigo());
        assertEquals(false, filas.get(1).conectado());
    }

    @Test
    void reemplazarDirectorioQuitaRegistrosObsoletosEnUnaOperacion() throws Exception {
        cache.guardar(usuario("OBSOLETO", false));

        cache.reemplazarDirectorio(List.of(usuario("A", true), usuario("B", false)));

        assertTrue(cache.porCodigo("OBSOLETO").isEmpty());
        assertEquals(List.of("A", "B"), cache.listar().stream().map(UsuarioLocal::codigo).toList());
    }
}
