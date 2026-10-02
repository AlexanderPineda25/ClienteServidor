package universidad.mensajeria.utilerias;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Salida de un filtro: archivos (entrada del siguiente) + metadatos
 * calculados SOLO por este filtro (hash, caracteres, palabras). Inmutable.
 */
public record ResultadoFiltro(List<File> archivos, Metadatos metadatos) {

    public ResultadoFiltro {
        archivos = List.copyOf(archivos);
    }

    /** Metadatos clave→valor de un filtro. Inmutable; la tuberia los fusiona. */
    public record Metadatos(Map<String, String> valores) {

        public static Metadatos vacios() {
            return new Metadatos(Map.of());
        }

        public static Metadatos de(String clave, String valor) {
            return new Metadatos(Map.of(clave, valor));
        }

        public String obtener(String clave) {
            return valores.get(clave);
        }

        /** Fusion: los valores del otro pisan los propios en colision. */
        public Metadatos fusionar(Metadatos otro) {
            Map<String, String> union = new LinkedHashMap<>(valores);
            union.putAll(otro.valores());
            return new Metadatos(Collections.unmodifiableMap(union));
        }
    }
}
