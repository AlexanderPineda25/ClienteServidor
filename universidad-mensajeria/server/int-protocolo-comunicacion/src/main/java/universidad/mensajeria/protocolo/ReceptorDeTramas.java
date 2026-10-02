package universidad.mensajeria.protocolo;

import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.tipos.Mensaje;

/**
 * Lo que el transporte necesita del mundo exterior (PLAN2 §2.5 Regla 2):
 * callback definido aqui e implementado por la Fachada.
 */
public interface ReceptorDeTramas {

    void alConectar(IdSesion sesion);

    void alRecibir(IdSesion sesion, Mensaje mensaje);

    void alDesconectar(IdSesion sesion, String motivo);

    /** Pool agotado: la conexion se rechaza con "servidor lleno" (RF-S46). */
    default void alRechazoPorSaturacion(String ip, int maximo) {
    }
}
