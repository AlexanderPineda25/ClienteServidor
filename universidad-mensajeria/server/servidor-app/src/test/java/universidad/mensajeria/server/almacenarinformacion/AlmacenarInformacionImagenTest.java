package universidad.mensajeria.server.almacenarinformacion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import universidad.mensajeria.common.interno.ArchivoDTO;
import universidad.mensajeria.common.interno.ArchivoGuardarDTO;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlmacenarInformacionImagenTest {

    @TempDir
    Path temporal;

    @Test
    void promocionaOriginalYDerivadasDentroDeLaCarpetaIdentificadaDelRemitente()
            throws Exception {
        Path work = Files.createDirectories(temporal.resolve("work/A001-tmp"));
        Path original = Files.write(work.resolve("foto.jpg"), new byte[]{1, 2, 3});
        for (int i = 1; i <= 5; i++) {
            Files.write(work.resolve("0" + i + "_filtro.png"), new byte[]{(byte) i});
        }
        Path uploads = temporal.resolve("uploads");
        AlmacenarInformacion almacenar = new AlmacenarInformacion(mock(PersistenciaPort.class),
                temporal.resolve("work"), uploads);

        var rutas = almacenar.promocionarAUploads("abc123", original.toFile(), "foto.jpg",
                "Ana María Pérez Gómez [A001]");

        assertEquals(6, rutas.size());
        assertTrue(rutas.get(0).startsWith("Ana María Pérez Gómez [A001]/abc123/"));
        assertTrue(rutas.stream().allMatch(r -> Files.exists(uploads.resolve(r))));
        assertTrue(Files.exists(uploads.resolve(rutas.get(0))));
        assertTrue(rutas.stream().anyMatch(r -> r.endsWith("05_filtro.png")));
    }

    @Test
    void deduplicaImagenPorPropietarioYHashNoGlobalmente() {
        PersistenciaPort persistencia = mock(PersistenciaPort.class);
        when(persistencia.buscarArchivoPorPropietarioYHash("A001", "same-hash"))
                .thenReturn(Optional.of(new ArchivoDTO(1, "a.png", "A001/same-hash/a.png",
                        10, "IMAGEN", "image/png", "same-hash")));
        when(persistencia.buscarArchivoPorPropietarioYHash("B002", "same-hash"))
                .thenReturn(Optional.empty());
        when(persistencia.guardarArchivo(any(ArchivoGuardarDTO.class)))
                .thenReturn(new ArchivoDTO(2, "b.png", "B002/same-hash/b.png",
                        10, "IMAGEN", "image/png", "same-hash"));
        AlmacenarInformacion almacenar = new AlmacenarInformacion(persistencia,
                temporal.resolve("work"), temporal.resolve("uploads"));

        assertEquals(1, almacenar.registrarImagen("a.png", "A001/same-hash/a.png", 10,
                "image/png", "same-hash", "A001").id());
        assertEquals(2, almacenar.registrarImagen("b.png", "B002/same-hash/b.png", 10,
                "image/png", "same-hash", "B002").id());

        verify(persistencia, never()).buscarArchivoPorHash("same-hash");
        verify(persistencia).guardarArchivo(any(ArchivoGuardarDTO.class));
    }
}
