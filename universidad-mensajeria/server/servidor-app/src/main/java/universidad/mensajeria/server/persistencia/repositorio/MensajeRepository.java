package universidad.mensajeria.server.persistencia.repositorio;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import universidad.mensajeria.server.persistencia.entidades.Mensaje;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Consultas JPQL del historial (cero SQL nativo: el Adapter §5.4 conmuta de
 * motor solo con configuración). Base del HISTORIAL_PAGE paginado (FASE 6).
 * Nota: @Param explícito porque el build no compila con -parameters.
 */
public interface MensajeRepository extends JpaRepository<Mensaje, Long> {

    @Query("""
            SELECT m FROM Mensaje m
            LEFT JOIN FETCH m.archivo
            WHERE m.id = :id""")
    Optional<Mensaje> buscarPorIdConArchivo(@Param("id") long id);

    @Query("""
            SELECT m FROM Mensaje m
            LEFT JOIN FETCH m.remitente
            LEFT JOIN FETCH m.destinatario
            LEFT JOIN FETCH m.archivo
            WHERE (m.remitente.id = :a AND m.destinatario.id = :b)
               OR (m.remitente.id = :b AND m.destinatario.id = :a)
            ORDER BY m.fechaEnvio ASC, m.id ASC""")
    Page<Mensaje> paginaConversacion(@Param("a") long a, @Param("b") long b,
                                     Pageable paginacion);

    long countByRemitente_Id(long remitenteId);

    /** FASE 6 — historial por códigos (sin necesidad de resolver IDs antes). */
    @Query("""
            SELECT m FROM Mensaje m
            LEFT JOIN FETCH m.remitente
            LEFT JOIN FETCH m.destinatario
            LEFT JOIN FETCH m.archivo
            WHERE (m.remitente.codigo = :a AND m.destinatario.codigo = :b)
               OR (m.remitente.codigo = :b AND m.destinatario.codigo = :a)
            ORDER BY m.fechaEnvio DESC, m.id DESC""")
    Page<Mensaje> paginaConversacionPorCodigo(@Param("a") String a, @Param("b") String b,
                                              Pageable paginacion);

    /** FASE 6 — mensajes recientes dirigidos a un usuario (SYNC_LOGIN). */
    @Query("""
            SELECT m FROM Mensaje m
            LEFT JOIN FETCH m.remitente
            LEFT JOIN FETCH m.destinatario
            LEFT JOIN FETCH m.archivo
            WHERE m.destinatario.codigo = :codigo
            ORDER BY m.fechaEnvio DESC""")
    Page<Mensaje> mensajesPendientesPorCodigo(@Param("codigo") String codigo,
                                              Pageable paginacion);

    /** FASE 9 — informe 3: histórico global por rango de fechas y/o código. */
    @Query("""
            SELECT m FROM Mensaje m
            LEFT JOIN FETCH m.remitente
            LEFT JOIN FETCH m.destinatario
            LEFT JOIN FETCH m.archivo
            WHERE m.fechaEnvio >= :desde AND m.fechaEnvio <= :hasta
              AND (:codigo = '' OR m.remitente.codigo = :codigo
                              OR m.destinatario.codigo = :codigo)
            ORDER BY m.fechaEnvio ASC, m.id ASC""")
    List<Mensaje> historico(@Param("desde") LocalDateTime desde,
                            @Param("hasta") LocalDateTime hasta,
                            @Param("codigo") String codigo);
}
