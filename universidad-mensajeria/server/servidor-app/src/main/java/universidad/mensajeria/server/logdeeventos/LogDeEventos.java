package universidad.mensajeria.server.logdeeventos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import universidad.mensajeria.common.interno.AuditoriaInformeDTO;
import universidad.mensajeria.common.interno.InformeFiltroDTO;
import universidad.mensajeria.common.interno.LoginConteoDTO;
import universidad.mensajeria.common.interno.TipoAccion;

import universidad.mensajeria.server.almacenarinformacion.InterfazAlmacenarInformacion;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * LogDeEventos del diagrama (§2.2): cada evento va a registro_acciones (req.
 * #3) via AlmacenarInformacion, a Logback (logs/server.log) y a suscriptores
 * en vivo (consola + escritorio, req. #12). Sin anotaciones de framework
 * (lo construye Fabrica). Jamas ve persistencia.entidades.
 */
public class LogDeEventos implements InterfazLogDeEventos {

    private static final Logger LOG = LoggerFactory.getLogger(LogDeEventos.class);

    private final InterfazAlmacenarInformacion almacenar;
    private final List<Consumer<String>> suscriptores = new CopyOnWriteArrayList<>();

    public LogDeEventos(InterfazAlmacenarInformacion almacenar) {
        this.almacenar = almacenar;
    }

    @Override
    public void suscribir(Consumer<String> observador) {
        suscriptores.add(observador);
    }

    @Override
    public void desuscribir(Consumer<String> observador) {
        suscriptores.remove(observador);
    }

    @Override
    public long registrar(TipoAccion tipo, String descripcion) {
        return registrar(tipo, descripcion, null, null, null);
    }

    @Override
    public long registrar(TipoAccion tipo, String descripcion,
                          String codigoUsuario, String ip, String detalles) {
        long id = almacenar.registrarAccion(tipo, descripcion, codigoUsuario, ip, detalles);
        String linea = "[%s] %s%s".formatted(tipo, descripcion,
                codigoUsuario == null ? "" : " (usuario=" + codigoUsuario + ")");
        LOG.info("{}", linea);
        suscriptores.forEach(suscriptor -> suscriptor.accept(linea));
        return id;
    }

    @Override
    public List<LoginConteoDTO> consultarConexiones(InformeFiltroDTO filtro) {
        return almacenar.conteoConexiones(filtro);
    }

    @Override
    public List<AuditoriaInformeDTO> consultar(InformeFiltroDTO filtro) {
        return almacenar.auditoriaInforme(filtro);
    }
}
