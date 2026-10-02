package universidad.mensajeria.server.persistencia.repositorio;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import universidad.mensajeria.server.persistencia.entidades.RegistroAccion;
import universidad.mensajeria.common.interno.TipoAccion;

import java.time.LocalDateTime;
import java.util.List;

public interface RegistroAccionRepository extends JpaRepository<RegistroAccion, Long> {

    Page<RegistroAccion> findByTipoOrderByFechaDesc(TipoAccion tipo, Pageable paginacion);

    Page<RegistroAccion> findAllByOrderByFechaDesc(Pageable paginacion);

    // FASE 9 — informe 4 (bitácora) y 2 (frecuencia, GROUP BY en JPQL)

    @Query("""
            SELECT a FROM RegistroAccion a
            LEFT JOIN FETCH a.usuario
            WHERE a.fecha >= :desde AND a.fecha <= :hasta
              AND (:codigo = '' OR a.usuario.codigo = :codigo)
            ORDER BY a.fecha DESC, a.id DESC""")
    List<RegistroAccion> buscarParaInforme(@Param("desde") LocalDateTime desde,
                                            @Param("hasta") LocalDateTime hasta,
                                            @Param("codigo") String codigo);

    @Query("""
            SELECT u.codigo AS codigo, COUNT(a) AS veces
            FROM RegistroAccion a
            JOIN a.usuario u
            WHERE a.tipo IN :tipos
              AND a.fecha >= :desde AND a.fecha <= :hasta
            GROUP BY u.codigo
            ORDER BY COUNT(a) DESC, u.codigo ASC""")
    List<ConteoConexion> conteoConexiones(@Param("tipos") List<TipoAccion> tipos,
                                         @Param("desde") LocalDateTime desde,
                                         @Param("hasta") LocalDateTime hasta);

    interface ConteoConexion {

        String getCodigo();

        long getVeces();
    }
}
