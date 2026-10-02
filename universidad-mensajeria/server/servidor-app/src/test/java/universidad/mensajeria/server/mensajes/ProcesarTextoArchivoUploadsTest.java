package universidad.mensajeria.server.mensajes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import universidad.mensajeria.common.interno.ArchivoDTO;
import universidad.mensajeria.common.interno.ArchivoGuardarDTO;
import universidad.mensajeria.common.interno.LimitesDTO;
import universidad.mensajeria.common.interno.MensajeGuardarDTO;
import universidad.mensajeria.common.interno.MensajeResumenDTO;
import universidad.mensajeria.common.interno.ResultadoMensajeDTO;
import universidad.mensajeria.common.interno.UsuarioDTO;
import universidad.mensajeria.server.almacenarinformacion.AlmacenarInformacion;
import universidad.mensajeria.server.almacenarinformacion.InterfazAlmacenarInformacion;
import universidad.mensajeria.server.almacenarinformacion.PersistenciaPort;
import universidad.mensajeria.utilerias.TuberiaFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * El texto tambien queda en uploads/ (subcarpeta "textos" por remitente) y los
 * archivos genericos en carpeta por remitente (sin subcarpeta).
 */
class ProcesarTextoArchivoUploadsTest {

    @TempDir
    Path base;

    private static UsuarioDTO usuario(String codigo) {
        return new UsuarioDTO(codigo, "Ana", "Lopez", "Sistemas", "hash", true);
    }

    private InterfazAlmacenarInformacion almacenar(Path work, Path uploads,
                                                   PersistenciaPort persistencia) {
        return new AlmacenarInformacion(persistencia, work, uploads);
    }

    private PersistenciaPort persistencia() {
        PersistenciaPort persistencia = mock(PersistenciaPort.class);
        when(persistencia.buscarUsuarioPorCodigo("A001")).thenReturn(Optional.of(usuario("A001")));
        when(persistencia.buscarUsuarioPorCodigo("B002")).thenReturn(Optional.of(usuario("B002")));
        when(persistencia.leerLimites()).thenReturn(new LimitesDTO(100, 3, 52_428_800L));
        when(persistencia.guardarMensaje(any(MensajeGuardarDTO.class)))
                .thenAnswer(inv -> {
                    MensajeGuardarDTO dto = inv.getArgument(0);
                    return new MensajeResumenDTO(7L, dto.remitente(), dto.destinatario(),
                            "TEXTO", dto.contenido(), dto.hashSha256(),
                            dto.numCaracteres(), dto.numPalabras(), null, null, null, null,
                            dto.etapasFiltrado(), "2026-10-02T00:00:00");
                });
        when(persistencia.guardarArchivo(any(ArchivoGuardarDTO.class)))
                .thenAnswer(inv -> {
                    ArchivoGuardarDTO dto = inv.getArgument(0);
                    return new ArchivoDTO(11L, dto.nombre(), dto.ruta(), dto.tamano(),
                            dto.tipo(), dto.mime(), dto.hashSha256());
                });
        return persistencia;
    }

    private InterfazMensajes mensajes(InterfazAlmacenarInformacion almacenar) {
        return new Mensajes(almacenar, new TuberiaFactory(), Runnable::run, etapa -> {
        });
    }

    @Test
    void textoQuedaEnSubcarpetaTextosPorRemitente() throws Exception {
        Path work = Files.createDirectories(base.resolve("work"));
        Path uploads = Files.createDirectories(base.resolve("uploads"));
        InterfazMensajes mensajes = mensajes(almacenar(work, uploads, persistencia()));

        ResultadoMensajeDTO dto = mensajes
                .procesarTexto("A001", "B002", "hola mundo", "127.0.0.1").get();

        assertEquals(1, dto.rutasDerivadas().size());
        assertTrue(dto.rutasDerivadas().get(0).startsWith("Ana Lopez [A001]/textos/"),
                dto.rutasDerivadas().toString());
        assertTrue(dto.rutasDerivadas().get(0).endsWith("00_original.txt"));
        assertTrue(Files.isRegularFile(uploads.resolve(dto.rutasDerivadas().get(0))));
        assertEquals("hola mundo", Files.readString(
                uploads.resolve(dto.rutasDerivadas().get(0)), StandardCharsets.UTF_8));
    }

    @Test
    void archivoGenericoQuedaEnCarpetaPorRemitente() throws Exception {
        Path work = Files.createDirectories(base.resolve("w2"));
        Path uploads = Files.createDirectories(base.resolve("u2"));
        InterfazMensajes mensajes = mensajes(almacenar(work, uploads, persistencia()));

        ResultadoMensajeDTO dto = mensajes.procesarArchivo("A001", "B002",
                new byte[]{1, 2, 3}, "doc.pdf", "application/pdf", "127.0.0.1").get();

        assertEquals(1, dto.rutasDerivadas().size());
        assertTrue(dto.rutasDerivadas().get(0).startsWith("Ana Lopez [A001]/"),
                dto.rutasDerivadas().toString());
        assertTrue(Files.isRegularFile(uploads.resolve(dto.rutasDerivadas().get(0))));
    }
}
