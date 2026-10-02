package universidad.mensajeria.utilerias;

/** Contrato para construir las tuberias disponibles para los mensajes. */
public interface InterfazTuberiaFactory {

    Tuberia texto();

    Tuberia imagen();

    Tuberia archivo();
}
