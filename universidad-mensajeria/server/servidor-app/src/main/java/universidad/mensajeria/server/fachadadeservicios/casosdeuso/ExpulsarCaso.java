package universidad.mensajeria.server.fachadadeservicios.casosdeuso;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.tipos.TipoMensaje;
import universidad.mensajeria.protocolo.InterfazProtocoloComunicacion;
import universidad.mensajeria.server.conexionesclientes.InterfazConexionesClientes;
import universidad.mensajeria.server.logdeeventos.InterfazLogDeEventos;
import universidad.mensajeria.common.interno.TipoAccion;

/** KICK propio: cierra las DEMAS sesiones del codigo (req. #10). */
public final class ExpulsarCaso {

    private static final Logger LOG = LoggerFactory.getLogger(ExpulsarCaso.class);

    private final InterfazProtocoloComunicacion protocolo;
    private final InterfazConexionesClientes conexiones;
    private final InterfazLogDeEventos eventos;
    private final ResponderCaso respuestas;

    public ExpulsarCaso(InterfazProtocoloComunicacion protocolo,
                        InterfazConexionesClientes conexiones,
                        InterfazLogDeEventos eventos,
                        ResponderCaso respuestas) {
        this.protocolo = protocolo;
        this.conexiones = conexiones;
        this.eventos = eventos;
        this.respuestas = respuestas;
    }

    public void expulsarOtrasSesiones(IdSesion sesion, String codigo, String idSolicitud) {
        int cerradas = 0;
        for (IdSesion otra : conexiones.sesionesDe(codigo)) {
            if (otra.id().equals(sesion.id())) {
                continue;
            }
            conexiones.remover(codigo, otra);
            protocolo.cerrarSesion(otra, "sesion cerrada por KICK");
            cerradas++;
        }
        eventos.registrar(TipoAccion.CIERRE_CONEXION,
                "KICK de %s: %d sesiones cerradas".formatted(codigo, cerradas),
                codigo, sesion.direccionIp(), null);
        LOG.info("KICK de {}: {} sesiones cerradas", codigo, cerradas);
        respuestas.exito(sesion, idSolicitud, TipoMensaje.ACK);
    }
}
