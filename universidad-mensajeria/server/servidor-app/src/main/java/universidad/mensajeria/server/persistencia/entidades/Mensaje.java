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

import java.time.LocalDateTime;

/**
 * Mensaje 1-a-1 persistido (tabla mensajes, §5.1).
 * Texto: contenido + hash_sha256 + num_caracteres + num_palabras.
 * Imagen: contenido null, archivo asociado + etapas_filtrado (JSON con las 5 rutas).
 */
@Entity
@Table(name = "mensajes")
public class Mensaje {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "remitente_id", nullable = false)
    private Usuario remitente;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "destinatario_id", nullable = false)
    private Usuario destinatario;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TipoContenido tipo;

    @Column(columnDefinition = "TEXT")
    private String contenido;

    @Column(name = "hash_sha256", length = 64)
    private String hashSha256;

    @Column(name = "num_caracteres")
    private Integer numCaracteres;

    @Column(name = "num_palabras")
    private Integer numPalabras;

    @Column(name = "etapas_filtrado", columnDefinition = "TEXT")
    private String etapasFiltrado;

    @Column(name = "fecha_envio", nullable = false)
    private LocalDateTime fechaEnvio;

    @Column(name = "ip_remitente", length = 45)
    private String ipRemitente;

    @Column(name = "ip_destinatario", length = 45)
    private String ipDestinatario;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "archivo_id")
    private Archivo archivo;

    protected Mensaje() {
    }

    private Mensaje(Usuario remitente, Usuario destinatario) {
        this.remitente = remitente;
        this.destinatario = destinatario;
        this.fechaEnvio = LocalDateTime.now();
    }

    public static Mensaje deTexto(Usuario remitente, Usuario destinatario, String contenido,
                                  String hashSha256, int numCaracteres, int numPalabras,
                                  String ipRemitente) {
        Mensaje mensaje = new Mensaje(remitente, destinatario);
        mensaje.tipo = TipoContenido.TEXTO;
        mensaje.contenido = contenido;
        mensaje.hashSha256 = hashSha256;
        mensaje.numCaracteres = numCaracteres;
        mensaje.numPalabras = numPalabras;
        mensaje.ipRemitente = ipRemitente;
        return mensaje;
    }

    public static Mensaje deImagen(Usuario remitente, Usuario destinatario, Archivo archivo,
                                   String hashSha256, String etapasFiltradoJson,
                                   String ipRemitente) {
        Mensaje mensaje = new Mensaje(remitente, destinatario);
        mensaje.tipo = TipoContenido.IMAGEN;
        mensaje.archivo = archivo;
        mensaje.hashSha256 = hashSha256;
        mensaje.etapasFiltrado = etapasFiltradoJson;
        mensaje.ipRemitente = ipRemitente;
        return mensaje;
    }

    public static Mensaje deArchivo(Usuario remitente, Usuario destinatario, Archivo archivo,
                                    String hashSha256, String ipRemitente) {
        Mensaje mensaje = new Mensaje(remitente, destinatario);
        mensaje.tipo = TipoContenido.ARCHIVO;
        mensaje.archivo = archivo;
        mensaje.hashSha256 = hashSha256;
        mensaje.ipRemitente = ipRemitente;
        return mensaje;
    }

    @PrePersist
    void alPersistir() {
        if (fechaEnvio == null) {
            fechaEnvio = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public Usuario getRemitente() { return remitente; }
    public Usuario getDestinatario() { return destinatario; }
    public TipoContenido getTipo() { return tipo; }
    public String getContenido() { return contenido; }
    public String getHashSha256() { return hashSha256; }
    public Integer getNumCaracteres() { return numCaracteres; }
    public Integer getNumPalabras() { return numPalabras; }
    public String getEtapasFiltrado() { return etapasFiltrado; }
    public LocalDateTime getFechaEnvio() { return fechaEnvio; }
    public String getIpRemitente() { return ipRemitente; }
    public Archivo getArchivo() { return archivo; }
}
