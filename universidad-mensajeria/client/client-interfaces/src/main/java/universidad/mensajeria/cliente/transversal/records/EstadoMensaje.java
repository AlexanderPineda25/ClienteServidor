package universidad.mensajeria.cliente.transversal.records;

/** Estado local del envío y confirmaciones remotas. */
public enum EstadoMensaje {
    PENDIENTE,
    ERROR,
    ENVIADO,
    ENTREGADO,
    LEIDO;

    public static EstadoMensaje desde(String valor) {
        if (valor == null || valor.isBlank()) {
            return ENVIADO;
        }
        try {
            return EstadoMensaje.valueOf(valor);
        } catch (IllegalArgumentException e) {
            return ENVIADO;
        }
    }
}
