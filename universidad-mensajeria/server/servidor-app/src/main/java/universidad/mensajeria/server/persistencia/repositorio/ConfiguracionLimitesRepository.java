package universidad.mensajeria.server.persistencia.repositorio;

import org.springframework.data.jpa.repository.JpaRepository;
import universidad.mensajeria.server.persistencia.entidades.ConfiguracionLimites;

import java.util.Optional;

public interface ConfiguracionLimitesRepository extends JpaRepository<ConfiguracionLimites, Long> {

    Optional<ConfiguracionLimites> findFirstByActivaTrue();
}
