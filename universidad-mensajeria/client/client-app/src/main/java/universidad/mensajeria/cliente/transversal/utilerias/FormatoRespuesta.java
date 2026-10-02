package universidad.mensajeria.cliente.transversal.utilerias;

import universidad.mensajeria.cliente.transversal.records.MensajeLocal;

/** Convierte una selección de respuesta en texto compatible con clientes antiguos. */
public final class FormatoRespuesta {

    private static final int MAX_CITA = 120;

    public static String prefijo(MensajeLocal citado, String identidad) {
        String referencia;
        if ("MENSAJE_IMAGEN".equals(citado.tipo())) {
            String nombre = citado.nombreArchivo() == null ? "imagen" : citado.nombreArchivo();
            referencia = "[Imagen: " + unaLinea(nombre) + "]";
        } else {
            String texto = unaLinea(citado.contenido() == null ? "mensaje" : citado.contenido());
            referencia = "\"" + recortar(texto) + "\"";
        }
        return "Respuesta a " + identidad + ": " + referencia + "\n\n";
    }

    private static String unaLinea(String texto) {
        return texto.replaceAll("\\s+", " ").trim();
    }

    private static String recortar(String texto) {
        int puntos = texto.codePointCount(0, texto.length());
        if (puntos <= MAX_CITA) {
            return texto;
        }
        return texto.substring(0, texto.offsetByCodePoints(0, MAX_CITA - 1)) + "…";
    }

    private FormatoRespuesta() {
    }
}
