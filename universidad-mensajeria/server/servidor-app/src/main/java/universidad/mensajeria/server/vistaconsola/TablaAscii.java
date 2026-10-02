package universidad.mensajeria.server.vistaconsola;

import universidad.mensajeria.common.interno.InformeDTO;

import java.util.ArrayList;
import java.util.List;

/**
 * Render de tablas ASCII para la consola (FASE 9, §8): solo presentación,
 * sin lógica de negocio. Las celdas largas (hash, JSON de etapas) se
 * recortan a ANCHO_MAX celda con "...".
 */
public final class TablaAscii {

    private static final int ANCHO_MAXIMO_CELDA = 60;

    private TablaAscii() {
    }

    public static String convertir(InformeDTO informe) {
        int columnas = informe.columnas().size();
        int[] anchos = new int[columnas];
        for (int i = 0; i < columnas; i++) {
            anchos[i] = cortar(informe.columnas().get(i)).length();
        }
        for (List<String> fila : informe.filas()) {
            for (int i = 0; i < columnas && i < fila.size(); i++) {
                anchos[i] = Math.max(anchos[i], cortar(fila.get(i)).length());
            }
        }

        String borde = borde(anchos);
        StringBuilder salida = new StringBuilder();
        salida.append(informe.titulo()).append('\n');
        salida.append(borde).append('\n');
        salida.append(celdas(informe.columnas(), anchos, columnas)).append('\n');
        salida.append(borde).append('\n');
        for (List<String> fila : informe.filas()) {
            salida.append(celdas(fila, anchos, columnas)).append('\n');
        }
        salida.append(borde).append('\n');
        salida.append(informe.filas().size())
                .append(informe.filas().size() == 1 ? " fila" : " filas");
        return salida.toString();
    }

    private static String borde(int[] anchos) {
        StringBuilder linea = new StringBuilder("+");
        for (int ancho : anchos) {
            linea.append("-".repeat(ancho + 2)).append('+');
        }
        return linea.toString();
    }

    private static String celdas(List<String> valores, int[] anchos, int columnas) {
        List<String> celdas = new ArrayList<>(columnas);
        for (int i = 0; i < columnas; i++) {
            String texto = i < valores.size() && valores.get(i) != null
                    ? cortar(valores.get(i)) : "";
            celdas.add(String.format("%-" + anchos[i] + "s", texto));
        }
        return "| " + String.join(" | ", celdas) + " |";
    }

    private static String cortar(String texto) {
        if (texto == null) {
            return "";
        }
        String limpio = texto.replace('\n', ' ').replace('\r', ' ');
        if (limpio.length() <= ANCHO_MAXIMO_CELDA) {
            return limpio;
        }
        return limpio.substring(0, ANCHO_MAXIMO_CELDA - 3) + "...";
    }
}
