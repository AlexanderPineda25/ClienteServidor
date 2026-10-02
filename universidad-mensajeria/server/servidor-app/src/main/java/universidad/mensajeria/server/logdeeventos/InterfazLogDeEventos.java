package universidad.mensajeria.server.logdeeventos;

import universidad.mensajeria.common.interno.AuditoriaInformeDTO;
import universidad.mensajeria.common.interno.InformeFiltroDTO;
import universidad.mensajeria.common.interno.LoginConteoDTO;
import universidad.mensajeria.common.interno.TipoAccion;

import java.util.List;
import java.util.function.Consumer;

/**
 * API publica del paquete (PLAN2 §2.5): suscriptor de eventos que persiste
 * auditoria via AlmacenarInformacion (E14). Firmas 100% DTO: jamas ve
 * persistencia.entidades. El registro de conexiones en MySQL lo dispara este
 * componente (Regla 6), nunca ConexionesClientes.
 * FASE 9 (§8): consultar/consultarConexiones alimentan los informes 2 y 4.
 */
public interface InterfazLogDeEventos {

    void suscribir(Consumer<String> observador);

    void desuscribir(Consumer<String> observador);

    long registrar(TipoAccion tipo, String descripcion);

    long registrar(TipoAccion tipo, String descripcion,
                   String codigoUsuario, String ip, String detalles);

    /** Informe 2: COUNT(*) GROUP BY usuario (JPQL, no calculo en memoria). */
    List<LoginConteoDTO> consultarConexiones(InformeFiltroDTO filtro);

    /** Informe 4: bitacora de auditoria filtrada. */
    List<AuditoriaInformeDTO> consultar(InformeFiltroDTO filtro);
}
