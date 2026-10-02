package universidad.mensajeria.common.interno;

/**
 * Filtro de informe (FASE 9, §8): fechas ISO (yyyy-MM-dd o yyyy-MM-ddTHH:mm)
 * y codigo de usuario; null/blank = sin restriccion. Las fechas se resuelven
 * a rangos en AlmacenarInformacion (JPQL); el codigo tambien se aplica como
 * filtro puro en Servicios.
 */
public record InformeFiltroDTO(String desde, String hasta, String codigo) {

    public static InformeFiltroDTO sinFiltro() {
        return new InformeFiltroDTO(null, null, null);
    }
}
