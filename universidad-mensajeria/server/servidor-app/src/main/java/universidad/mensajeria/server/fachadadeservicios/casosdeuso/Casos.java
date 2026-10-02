package universidad.mensajeria.server.fachadadeservicios.casosdeuso;

/**
 * Haz de casos de uso de la Fachada (PLAN2 §2.5 Regla 3): una clase por
 * operacion para no hacer una clase-dios. Los construye Fabrica.
 */
public record Casos(
        AutenticarCaso autenticar,
        CerrarCaso cerrar,
        ExpulsarCaso expulsar,
        CerrarAdminCaso cerrarAdmin,
        EncolarCaso encolar,
        FragmentoArchivoCaso fragmentos,
        HistorialCaso historial,
        DescargaCaso descarga,
        ListarCaso listar,
        ResponderCaso responder
) {
}
