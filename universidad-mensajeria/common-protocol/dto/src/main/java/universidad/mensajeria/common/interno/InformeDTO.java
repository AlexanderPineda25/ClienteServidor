package universidad.mensajeria.common.interno;

import java.util.List;

/**
 * Informe listo para presentar (FASE 9, §8): columnas + filas de texto.
 * Consola (tabla ASCII), escritorio (TableView) y export CSV consumen ESTE
 * DTO sin logica alguna (la UI no calcula, solo pinta).
 */
public record InformeDTO(String titulo, List<String> columnas, List<List<String>> filas) {

    /** Titulos de columna abreviados para CSV/CSV con nombre de archivo. */
    public String nombreArchivoCsv() {
        return titulo == null || titulo.isBlank()
                ? "informe"
                : titulo.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("_+$", "");
    }
}
