package universidad.mensajeria.server.persistencia.entidades;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import universidad.mensajeria.common.interno.TipoAccion;

import java.time.LocalDateTime;

/**
 * Evento de auditoría (tabla registro_acciones).
 * Lo escribe LogDeEventos vía AlmacenarInformacion; Logback escribe además
 * el archivo plano logs/server.log (ambos exigidos por la rama MENSAJERIA).
 */
@Entity
@Table(name = "registro_acciones")
public class RegistroAccion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TipoAccion tipo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String descripcion;

    @Column(nullable = false)
    private LocalDateTime fecha;

    @Column(length = 45)
    private String ip;

    @Column(columnDefinition = "TEXT")
    private String detalles;

    protected RegistroAccion() {
    }

    public RegistroAccion(TipoAccion tipo, Usuario usuario, String descripcion,
                          String ip, String detalles) {
        this.tipo = tipo;
        this.usuario = usuario;
        this.descripcion = descripcion;
        this.ip = ip;
        this.detalles = detalles;
        this.fecha = LocalDateTime.now();
    }

    @PrePersist
    void alPersistir() {
        if (fecha == null) {
            fecha = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public TipoAccion getTipo() { return tipo; }
    public Usuario getUsuario() { return usuario; }
    public String getDescripcion() { return descripcion; }
    public LocalDateTime getFecha() { return fecha; }
    public String getIp() { return ip; }
    public String getDetalles() { return detalles; }
}
