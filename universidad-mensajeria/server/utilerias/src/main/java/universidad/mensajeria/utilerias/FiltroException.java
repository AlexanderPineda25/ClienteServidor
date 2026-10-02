package universidad.mensajeria.utilerias;

/** Fallo de una etapa: detiene la cadena sin resultado parcial (§3.4). */
public class FiltroException extends Exception {

    public FiltroException(String etapa, String mensaje) {
        super("[" + etapa + "] " + mensaje);
    }

    public FiltroException(String etapa, String mensaje, Throwable causa) {
        super("[" + etapa + "] " + mensaje, causa);
    }
}
