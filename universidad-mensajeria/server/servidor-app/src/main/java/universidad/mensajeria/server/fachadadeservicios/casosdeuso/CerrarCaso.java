package universidad.mensajeria.server.fachadadeservicios.casosdeuso;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.protocolo.InterfazProtocoloComunicacion;
import universidad.mensajeria.server.logdeeventos.InterfazLogDeEventos;
import universidad.mensajeria.common.interno.TipoAccion;


/** LOGOUT: cierra únicamente la sesión que envió la solicitud. */
public final class CerrarCaso {

    private static final Logger LOG = LoggerFactory.getLogger(CerrarCaso.class);

    private final InterfazProtocoloComunicacion protocolo;
    private final InterfazLogDeEventos eventos;

    public CerrarCaso(InterfazProtocoloComunicacion protocolo,
                      InterfazLogDeEventos eventos) {
        this.protocolo = protocolo;
        this.eventos = eventos;
    }

    public void cerrar(IdSesion sesion, String idSolicitud) {
        String codigo = sesion.codigo();
        if (codigo == null) {
            protocolo.cerrarSesion(sesion, "sesion cerrada");
            return;
        }
        protocolo.cerrarSesion(sesion, "sesion cerrada");
        eventos.registrar(TipoAccion.CIERRE_CONEXION,
                "LOGOUT de %s (1 sesión cerrada)".formatted(codigo),
                codigo, sesion.direccionIp(), null);
        LOG.info("LOGOUT de {} (sesión actual cerrada)", codigo);
    }

}
