package universidad.mensajeria.server.fachadadeservicios.casosdeuso;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.interno.TipoAccion;
import universidad.mensajeria.protocolo.InterfazProtocoloComunicacion;
import universidad.mensajeria.server.conexionesclientes.InterfazConexionesClientes;
import universidad.mensajeria.server.logdeeventos.InterfazLogDeEventos;

import java.util.ArrayList;
import java.util.List;

/**
 * Cierre administrativo: tumba sesiones de CUALQUIER codigo (req. #10,
 * decision de producto). Objetivo: codigo, id de sesion o "*" (todas).
 * Avisa CLOSE_NOTICE con el motivo y audita CIERRE_CONEXION.
 */
public final class CerrarAdminCaso {

    private static final Logger LOG = LoggerFactory.getLogger(CerrarAdminCaso.class);

    private final InterfazProtocoloComunicacion protocolo;
    private final InterfazConexionesClientes conexiones;
    private final InterfazLogDeEventos eventos;

    public CerrarAdminCaso(InterfazProtocoloComunicacion protocolo,
                          InterfazConexionesClientes conexiones,
                          InterfazLogDeEventos eventos) {
        this.protocolo = protocolo;
        this.conexiones = conexiones;
        this.eventos = eventos;
    }

    public int cerrar(String objetivo, String motivo) {
        if (objetivo == null || objetivo.isBlank()) {
            throw new IllegalArgumentException("indica un codigo, un id de sesion o '*'");
        }
        String causa = motivo == null || motivo.isBlank() ? "cierre administrativo" : motivo.trim();
        List<SesionObjetivo> blancos = resolver(objetivo.trim());
        if (blancos.isEmpty()) {
            throw new IllegalArgumentException("sin sesiones vivas para: " + objetivo);
        }
        int cerradas = 0;
        for (SesionObjetivo blanco : blancos) {
            if (conexiones.remover(blanco.codigo, blanco.sesion)) {
                protocolo.cerrarSesion(blanco.sesion, causa);
                cerradas++;
            }
        }
        eventos.registrar(TipoAccion.CIERRE_CONEXION,
                "Cierre administrativo de %s: %d sesiones cerradas (%s)".formatted(
                        objetivo, cerradas, causa),
                null, null, null);
        LOG.info("Cierre administrativo de {}: {} sesiones", objetivo, cerradas);
        return cerradas;
    }

    private List<SesionObjetivo> resolver(String objetivo) {
        List<SesionObjetivo> blancos = new ArrayList<>();
        if ("*".equals(objetivo)) {
            for (String codigo : conexiones.codigosConectados()) {
                for (IdSesion sesion : conexiones.sesionesDe(codigo)) {
                    blancos.add(new SesionObjetivo(codigo, sesion));
                }
            }
            return blancos;
        }
        if (!conexiones.sesionesDe(objetivo).isEmpty()) {
            for (IdSesion sesion : conexiones.sesionesDe(objetivo)) {
                blancos.add(new SesionObjetivo(objetivo, sesion));
            }
            return blancos;
        }
        for (String codigo : conexiones.codigosConectados()) {
            for (IdSesion sesion : conexiones.sesionesDe(codigo)) {
                if (sesion.id().equals(objetivo)) {
                    blancos.add(new SesionObjetivo(codigo, sesion));
                    return blancos;
                }
            }
        }
        return blancos;
    }

    private record SesionObjetivo(String codigo, IdSesion sesion) {
    }
}
