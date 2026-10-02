package universidad.mensajeria.server.persistencia.repositorio;

import org.springframework.data.jpa.repository.JpaRepository;
import universidad.mensajeria.server.persistencia.entidades.Usuario;

import java.util.List;
import java.util.Optional;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByCodigo(String codigo);

    boolean existsByCodigo(String codigo);

    List<Usuario> findAllByOrderByApellidosAsc();
}
