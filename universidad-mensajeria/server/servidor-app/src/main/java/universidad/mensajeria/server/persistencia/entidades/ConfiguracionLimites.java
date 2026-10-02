package universidad.mensajeria.server.persistencia.entidades;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Límites operativos (tabla configuracion_limites, §5.1).
 * Una sola fila activa; se lee por configuración, nunca se hardcodea.
 */
@Entity
@Table(name = "configuracion_limites")
public class ConfiguracionLimites {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "max_conexiones_usuario", nullable = false)
    private int maxConexionesUsuario = 3;

    @Column(name = "max_conexiones_totales", nullable = false)
    private int maxConexionesTotales = 100;

    @Column(name = "max_archivos_usuario", nullable = false)
    private int maxArchivosUsuario = 100;

    @Column(name = "max_tamano_archivo", nullable = false)
    private long maxTamanoArchivo = 52_428_800;

    @Column(name = "max_archivos_dia", nullable = false)
    private int maxArchivosDia = 20;

    @Column(nullable = false)
    private boolean activa = true;

    public Long getId() { return id; }
    public int getMaxConexionesUsuario() { return maxConexionesUsuario; }
    public int getMaxConexionesTotales() { return maxConexionesTotales; }
    public int getMaxArchivosUsuario() { return maxArchivosUsuario; }
    public long getMaxTamanoArchivo() { return maxTamanoArchivo; }
    public int getMaxArchivosDia() { return maxArchivosDia; }
    public boolean isActiva() { return activa; }
}
