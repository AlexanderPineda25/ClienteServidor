package universidad.mensajeria.server.conexionesclientes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import universidad.mensajeria.common.interno.IdSesion;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mapa VIVO de sesiones (§5.3): ConcurrentHashMap&lt;codigo, Set&lt;IdSesion&gt;&gt;.
 * SOLO memoria y SIN flechas salientes: no envia, no cierra sockets, no
 * persiste (el envio lo hace comp-protocolo-comunicacion; el registro en
 * MySQL lo dispara LogDeEventos). Un codigo, varias sesiones (req. #10).
 */
public class ConexionesClientes implements InterfazConexionesClientes {

    private static final Logger LOG = LoggerFactory.getLogger(ConexionesClientes.class);

    private final ConcurrentHashMap<String, Set<IdSesion>> sesionesVivas = new ConcurrentHashMap<>();

    @Override
    public void registrar(String codigo, IdSesion sesion) {
        sesionesVivas.computeIfAbsent(codigo, c -> ConcurrentHashMap.newKeySet()).add(sesion);
        LOG.info("Sesion registrada para {} (sesiones del codigo: {})", codigo, sesionesDe(codigo).size());
    }

    @Override
    public boolean remover(String codigo, IdSesion sesion) {
        Set<IdSesion> sesiones = sesionesVivas.get(codigo);
        if (sesiones == null) {
            return false;
        }
        boolean estaba = sesiones.removeIf(s -> s.id().equals(sesion.id()));
        if (sesiones.isEmpty()) {
            sesionesVivas.remove(codigo);
        }
        LOG.info("Sesion eliminada para {}", codigo);
        return estaba;
    }

    @Override
    public Set<IdSesion> sesionesDe(String codigo) {
        return Set.copyOf(sesionesVivas.getOrDefault(codigo, Set.of()));
    }

    @Override
    public List<String> codigosConectados() {
        return sesionesVivas.keySet().stream().sorted().toList();
    }

    @Override
    public int cantidadUsuariosConectados() {
        return sesionesVivas.size();
    }

    @Override
    public int totalSesiones() {
        return sesionesVivas.values().stream().mapToInt(Set::size).sum();
    }

    @Override
    public void limpiar() {
        sesionesVivas.clear();
    }
}
