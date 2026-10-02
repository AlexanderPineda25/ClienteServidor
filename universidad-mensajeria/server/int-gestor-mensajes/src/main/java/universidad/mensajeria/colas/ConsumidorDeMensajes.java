package universidad.mensajeria.colas;

import universidad.mensajeria.common.tipos.Mensaje;

/**
 * Callback de procesamiento (PLAN2 §2.5 Regla 2): lo implementa la Fachada
 * para encadenar Mensajes.procesarX al encolar.
 */
public interface ConsumidorDeMensajes {

    void procesar(Mensaje mensaje);
}
