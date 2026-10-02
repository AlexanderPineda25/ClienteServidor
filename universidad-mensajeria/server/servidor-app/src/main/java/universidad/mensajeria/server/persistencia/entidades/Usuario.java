package universidad.mensajeria.server.persistencia.entidades;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** Usuario de la comunidad: codigo, nombres, apellidos, contrasena (hash) y programa. */
@Entity
@Table(name = "usuarios")
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String codigo;

    @Column(nullable = false, length = 100)
    private String nombres;

    @Column(nullable = false, length = 100)
    private String apellidos;

    @Column(name = "password_hash", nullable = false, length = 120)
    private String passwordHash;

    @Column(name = "programa_academico", nullable = false, length = 120)
    private String programaAcademico;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstadoUsuario estado = EstadoUsuario.PENDIENTE;

    @Column(nullable = false)
    private boolean conectado = false;

    @Column(name = "fecha_ultima_conexion")
    private LocalDateTime fechaUltimaConexion;

    @Column(name = "fecha_registro", nullable = false)
    private LocalDateTime fechaRegistro;

    @Column(nullable = false)
    private boolean activo = true;

    @PrePersist
    void alPersistir() {
        if (fechaRegistro == null) {
            fechaRegistro = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public String getCodigo() { return codigo; }
    public void setCodigo(String codigo) { this.codigo = codigo; }
    public String getNombres() { return nombres; }
    public void setNombres(String nombres) { this.nombres = nombres; }
    public String getApellidos() { return apellidos; }
    public void setApellidos(String apellidos) { this.apellidos = apellidos; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getProgramaAcademico() { return programaAcademico; }
    public void setProgramaAcademico(String programaAcademico) { this.programaAcademico = programaAcademico; }
    public EstadoUsuario getEstado() { return estado; }
    public void setEstado(EstadoUsuario estado) { this.estado = estado; }
    public boolean isConectado() { return conectado; }
    public void setConectado(boolean conectado) { this.conectado = conectado; }
    public LocalDateTime getFechaUltimaConexion() { return fechaUltimaConexion; }
    public void setFechaUltimaConexion(LocalDateTime fecha) { this.fechaUltimaConexion = fecha; }
    public LocalDateTime getFechaRegistro() { return fechaRegistro; }
    public boolean isActivo() { return activo; }
    public void setActivo(boolean activo) { this.activo = activo; }

    public String nombreCompleto() {
        return nombres + " " + apellidos;
    }
}
