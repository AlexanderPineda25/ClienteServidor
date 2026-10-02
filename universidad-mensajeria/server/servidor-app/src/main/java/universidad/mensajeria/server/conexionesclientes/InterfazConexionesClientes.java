package universidad.mensajeria.server.conexionesclientes;

import universidad.mensajeria.common.interno.IdSesion;

import java.util.List;
import java.util.Set;

/**
 * API publica del paquete (PLAN2 §2.5): mapa en memoria codigo → sesiones.
 * Sin flechas salientes: no envia, no cierra, no persiste.
 */
public interface InterfazConexionesClientes {

    void registrar(String codigo, IdSesion sesion);

    /** Desregistra; true si seguia registrada (corte abrupto). */
    boolean remover(String codigo, IdSesion sesion);

    Set<IdSesion> sesionesDe(String codigo);

    List<String> codigosConectados();

    int cantidadUsuariosConectados();

    int totalSesiones();

    void limpiar();
}
