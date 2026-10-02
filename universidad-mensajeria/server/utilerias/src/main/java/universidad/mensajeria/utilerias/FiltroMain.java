package universidad.mensajeria.utilerias;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import universidad.mensajeria.utilerias.archivo.Sha256ArchivoFiltro;
import universidad.mensajeria.utilerias.imagen.BrilloFiltro;
import universidad.mensajeria.utilerias.imagen.EscalaGrisesFiltro;
import universidad.mensajeria.utilerias.imagen.GiroFiltro;
import universidad.mensajeria.utilerias.imagen.ReduccionFiltro;
import universidad.mensajeria.utilerias.imagen.SepiaFiltro;
import universidad.mensajeria.utilerias.imagen.Sha256ImagenFiltro;
import universidad.mensajeria.utilerias.texto.ContarCaracteresFiltro;
import universidad.mensajeria.utilerias.texto.ContarPalabrasFiltro;
import universidad.mensajeria.utilerias.texto.Sha256Filtro;

/**
 * Ejecucion multiproceso de un filtro (PLAN2 §3.6, extra opcional):
 * {@code FiltroMain <filtro> [opciones]} lee rutas por stdin (una por linea)
 * y escribe las rutas resultantes por stdout. Equivale a {@code P1 | P2 | P3}
 * de la pizarra; los procesos se enlazan con {@code ProcessBuilder.startPipeline}.
 * El servidor usa el modo en memoria.
 *
 * Filtros disponibles: sha256-texto, contar-caracteres, contar-palabras,
 * sha256-imagen, grises, sepia, giro[:grados], brillo[:factor],
 * reduccion[:factor], sha256-archivo.
 */
public final class FiltroMain {

    private static final Map<String, Function<String, Filtro>> REGISTRO = Map.ofEntries(
            Map.entry("sha256-texto", opcion -> new Sha256Filtro()),
            Map.entry("contar-caracteres", opcion -> new ContarCaracteresFiltro()),
            Map.entry("contar-palabras", opcion -> new ContarPalabrasFiltro()),
            Map.entry("sha256-imagen", opcion -> new Sha256ImagenFiltro()),
            Map.entry("grises", opcion -> new EscalaGrisesFiltro()),
            Map.entry("sepia", opcion -> new SepiaFiltro()),
            Map.entry("giro", opcion -> new GiroFiltro(
                    opcion.isBlank() ? 180 : Integer.parseInt(opcion))),
            Map.entry("brillo", opcion -> new BrilloFiltro(
                    opcion.isBlank() ? 1.25 : Double.parseDouble(opcion))),
            Map.entry("reduccion", opcion -> new ReduccionFiltro(
                    opcion.isBlank() ? 0.5 : Double.parseDouble(opcion))),
            Map.entry("sha256-archivo", opcion -> new Sha256ArchivoFiltro()));

    private FiltroMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Uso: FiltroMain <filtro> [opciones]");
            System.exit(2);
        }
        Filtro filtro = crear(args[0]);
        Path trabajo = Files.createTempDirectory("tuberia-");
        ContextoTuberia ctx = ContextoTuberia.sinOyente(trabajo);
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String linea;
            while ((linea = in.readLine()) != null) {
                if (linea.isBlank()) {
                    continue;
                }
                ResultadoFiltro resultado = filtro.ejecutar(List.of(new File(linea.trim())), ctx);
                for (File salida : resultado.archivos()) {
                    System.out.println(salida.getAbsolutePath());
                }
            }
        }
    }

    /** Fabrica por nombre (visible para tests). */
    static Filtro crear(String especificacion) {
        String[] partes = especificacion.split(":", 2);
        String nombre = partes[0];
        String opcion = partes.length > 1 ? partes[1] : "";
        Function<String, Filtro> creador = REGISTRO.get(nombre);
        if (creador == null) {
            throw new IllegalArgumentException("Filtro desconocido: " + especificacion);
        }
        return creador.apply(opcion);
    }

    /** Rutas resultantes de ejecutar la especificacion sobre una entrada. */
    static List<File> ejecutarUna(String especificacion, File entrada, Path trabajo) throws Exception {
        List<File> salidas = new ArrayList<>();
        ResultadoFiltro resultado = crear(especificacion)
                .ejecutar(List.of(entrada), ContextoTuberia.sinOyente(trabajo));
        salidas.addAll(resultado.archivos());
        return salidas;
    }
}
