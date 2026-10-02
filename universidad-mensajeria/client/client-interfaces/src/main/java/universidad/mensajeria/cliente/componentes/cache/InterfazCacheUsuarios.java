package universidad.mensajeria.cliente.componentes.cache;

import universidad.mensajeria.cliente.transversal.records.UsuarioLocal;

import java.util.List;
import java.util.Optional;

/** Caché local de usuarios (InterfazCacheUsuarios → adaptador H2). */
public interface InterfazCacheUsuarios {

    void guardar(UsuarioLocal usuario) throws Exception;

    default void reemplazarDirectorio(List<UsuarioLocal> usuarios) throws Exception {
        for (UsuarioLocal usuario : usuarios) {
            guardar(usuario);
        }
    }

    List<UsuarioLocal> listar() throws Exception;

    Optional<UsuarioLocal> porCodigo(String codigo) throws Exception;

    void marcarConectados(List<String> codigos) throws Exception;
}
