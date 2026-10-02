package universidad.mensajeria.clienteservidor;

/**
 * SPI del componente (PLAN2 §2.4).
 */
public interface ProveedorClienteServidor {

    InterfazClienteServidor crear();

    /**
     * Variante cableada: despachador con la salida ya conectada (la usa
     * Fabrica para romper el ciclo Fachada&lt;-&gt;Despachador).
     */
    default InterfazClienteServidor crear(SalidaClienteServidor.FachadaSalida salida) {
        InterfazClienteServidor despachador = crear();
        despachador.registrarSalida(salida);
        return despachador;
    }
}
