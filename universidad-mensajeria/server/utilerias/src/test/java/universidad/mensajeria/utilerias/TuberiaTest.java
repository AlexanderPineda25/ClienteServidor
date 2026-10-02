package universidad.mensajeria.utilerias;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import universidad.mensajeria.common.interno.EventoEtapa;
import universidad.mensajeria.utilerias.ResultadoFiltro.Metadatos;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Framework: orden secuencial, fusion de metadatos, traza y fallo sin parcial. */
class TuberiaTest {

    @TempDir
    Path trabajo;

    private static Filtro sufijo(String nombre, String sufijo, String clave, String valor) {
        return new Filtro() {
            @Override
            public ResultadoFiltro ejecutar(List<File> entrada, ContextoTuberia ctx) {
                return new ResultadoFiltro(entrada, Metadatos.de(clave, valor + sufijo));
            }

            @Override
            public String nombre() {
                return nombre;
            }
        };
    }

    @Test
    void ejecutaEnOrdenYFusionaMetadatosConTraza() throws Exception {
        List<EventoEtapa> eventos = new ArrayList<>();
        ContextoTuberia ctx = new ContextoTuberia(trabajo, eventos::add);
        File f = Files.writeString(trabajo.resolve("a.txt"), "x").toFile();

        Tuberia.ResultadoTuberia resultado = new Tuberia()
                .agregar(sufijo("A", "-a", "k1", "v"))
                .agregar(sufijo("B", "-b", "k2", "w"))
                .ejecutar(List.of(f), ctx);

        assertEquals(List.of(f), resultado.archivos());
        assertEquals("v-a", resultado.metadatos().obtener("k1"));
        assertEquals("w-b", resultado.metadatos().obtener("k2"));
        assertEquals(List.of("A", "B"), resultado.traza().stream().map(EventoEtapa::etapa).toList());
        assertEquals(2, eventos.size());
        assertTrue(eventos.stream().allMatch(e -> e.hilo() != null && !e.hilo().isBlank()));
    }

    @Test
    void falloDetieneLaCadenaSinResultado() {
        Filtro roto = new Filtro() {
            @Override
            public ResultadoFiltro ejecutar(List<File> entrada, ContextoTuberia ctx) throws FiltroException {
                throw new FiltroException("Roto", "boom");
            }

            @Override
            public String nombre() {
                return "Roto";
            }
        };
        Tuberia tuberia = new Tuberia()
                .agregar(sufijo("A", "", "k", "v"))
                .agregar(roto)
                .agregar(sufijo("C", "", "k2", "v2"));

        FiltroException fallo = assertThrows(FiltroException.class,
                () -> tuberia.ejecutar(List.of(trabajo.toFile()), ContextoTuberia.sinOyente(trabajo)));
        assertTrue(fallo.getMessage().contains("Roto"));
    }
}
