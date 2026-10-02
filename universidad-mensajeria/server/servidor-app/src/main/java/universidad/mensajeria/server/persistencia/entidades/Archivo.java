package universidad.mensajeria.server.persistencia.entidades;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** Archivo subido: original de imagen + metadatos (tabla archivos, §5.1). */
@Entity
@Table(name = "archivos")
public class Archivo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String nombre;

    @Column(nullable = false, unique = true, length = 767)
    private String ruta;

    @Column(nullable = false)
    private long tamano;

    @Column(nullable = false, length = 30)
    private String tipo;

    @Column(length = 255)
    private String mime;

    @Column(name = "hash_sha256", nullable = false, length = 64)
    private String hashSha256;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "propietario_id", nullable = false)
    private Usuario propietario;

    @Column(name = "fecha_subida", nullable = false)
    private LocalDateTime fechaSubida;

    protected Archivo() {
    }

    public Archivo(String nombre, String ruta, long tamano, String tipo,
                   String mime, String hashSha256, Usuario propietario) {
        this.nombre = nombre;
        this.ruta = ruta;
        this.tamano = tamano;
        this.tipo = tipo;
        this.mime = mime;
        this.hashSha256 = hashSha256;
        this.propietario = propietario;
        this.fechaSubida = LocalDateTime.now();
    }

    @PrePersist
    void alPersistir() {
        if (fechaSubida == null) {
            fechaSubida = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public String getNombre() { return nombre; }
    public String getRuta() { return ruta; }
    public long getTamano() { return tamano; }
    public String getTipo() { return tipo; }
    public String getMime() { return mime; }
    public String getHashSha256() { return hashSha256; }
    public Usuario getPropietario() { return propietario; }
    public LocalDateTime getFechaSubida() { return fechaSubida; }
}
