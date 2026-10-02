package universidad.mensajeria.server.mensajes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import universidad.mensajeria.common.interno.ArchivoDTO;
import universidad.mensajeria.common.interno.EventoEtapa;
import universidad.mensajeria.common.interno.LimitesDTO;
import universidad.mensajeria.common.interno.TipoAccion;
import universidad.mensajeria.common.interno.UsuarioDTO;
import universidad.mensajeria.server.almacenarinformacion.InterfazAlmacenarInformacion;
import universidad.mensajeria.server.logdeeventos.InterfazLogDeEventos;
import universidad.mensajeria.server.logdeeventos.LogDeEventos;
import universidad.mensajeria.utilerias.TuberiaFactory;

import java.nio.charset.StandardCharsets;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mensajes + LogDeEventos (PLAN2 §3): el unico que compone tuberias, eventos
 * por oyente. Todo con DTOs: ningun test ve persistencia.entidades.
 */
class MensajesTest {

    @TempDir
    Path base;

    private static UsuarioDTO usuario(String codigo) {
        return new UsuarioDTO(codigo, "Nombre " + codigo, "Apellido",
                "Ingenieria de Sistemas", "hash", true);
    }

    private InterfazAlmacenarInformacion almacenar() throws Exception {
        InterfazAlmacenarInformacion almacenar = mock(InterfazAlmacenarInformacion.class);
        when(almacenar.buscarUsuario("A001")).thenReturn(Optional.of(usuario("A001")));
        when(almacenar.buscarUsuario("B002")).thenReturn(Optional.of(usuario("B002")));
        when(almacenar.leerLimites()).thenReturn(new LimitesDTO(100, 3, 10_485_760L));
        when(almacenar.guardarMensajeTexto(anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyString())).thenReturn(7L);
        when(almacenar.guardarOriginalTexto(anyString(), anyString())).thenAnswer(inv -> {
            Path destino = base.resolve("original.txt");
            Files.writeString(destino, inv.getArgument(0), StandardCharsets.UTF_8);
            return destino.toFile();
        });
        when(almacenar.promocionarAUploads(anyString(), any(), anyString()))
                .thenReturn(List.of("hash/00_original.txt"));
        when(almacenar.registrarAccion(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(42L);
        return almacenar;
    }

    private InterfazMensajes mensajes(InterfazAlmacenarInformacion almacenar,
                                      List<EventoEtapa> eventos) {
        return new Mensajes(almacenar, new TuberiaFactory(), Runnable::run, eventos::add);
    }

    @Test
    void procesarTextoDevuelveDtoConTrazaYEventos() throws Exception {
        List<EventoEtapa> eventos = new ArrayList<>();
        InterfazAlmacenarInformacion almacenar = almacenar();

        var dto = mensajes(almacenar, eventos)
                .procesarTexto("A001", "B002", "hola mundo", "127.0.0.1").get();

        assertEquals(7L, dto.mensajeId());
        assertEquals("0b894166d3336435c800bea36ff21b29eaa801a52f584c006c49289a0dcf6e2f",
                dto.hashSha256());
        assertEquals(10, dto.numCaracteres());
        assertEquals(2, dto.numPalabras());
        assertEquals(3, dto.traza().size());
        assertEquals(3, eventos.size());
        assertEquals("SHA256", eventos.get(0).etapa());
        verify(almacenar).guardarMensajeTexto(eq("A001"), eq("B002"), eq("hola mundo"),
                eq("0b894166d3336435c800bea36ff21b29eaa801a52f584c006c49289a0dcf6e2f"),
                eq(10), eq(2), eq("127.0.0.1"));
    }

    @Test
    void imagenProcesadaUsaLaIdentidadDelRemitenteParaLaCarpetaDeUploads() throws Exception {
        InterfazAlmacenarInformacion almacenar = almacenar();
        when(almacenar.guardarOriginalBinario(any(), anyString(), anyString())).thenAnswer(inv -> {
            Path carpeta = Files.createDirectories(base.resolve("A001-tmp"));
            Path archivo = carpeta.resolve(inv.getArgument(2, String.class));
            Files.write(archivo, inv.getArgument(0, byte[].class));
            return archivo.toFile();
        });
        when(almacenar.promocionarAUploads(anyString(), any(), anyString(), anyString()))
                .thenReturn(List.of("Nombre A001 Apellido [A001]/hash/00_original.png"));
        when(almacenar.registrarImagen(anyString(), anyString(), anyLong(), anyString(),
                anyString(), anyString())).thenReturn(new ArchivoDTO(11, "foto.png",
                "Nombre A001 Apellido [A001]/hash/00_original.png", 128, "IMAGEN",
                "image/png", "hash"));
        when(almacenar.guardarMensajeImagen(anyString(), anyString(), anyString(), anyString(),
                anyLong(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(77L);
        BufferedImage imagen = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        ImageIO.write(imagen, "png", salida);

        var resultado = mensajes(almacenar, new ArrayList<>())
                .procesarImagen("A001", "B002", salida.toByteArray(), "foto.png",
                        "image/png", "127.0.0.1").get();

        assertEquals(77L, resultado.mensajeId());
        assertEquals(11L, resultado.archivoId());
        verify(almacenar).promocionarAUploads(anyString(), any(), eq("foto.png"),
                eq("Nombre A001 Apellido [A001]"));
    }

    @Test
    void imagenSinMagicValidoSeRechazaAntesDelPipeline() throws Exception {
        var futuro = mensajes(almacenar(), new ArrayList<>())
                .procesarImagen("A001", "B002",
                        "soy texto, no imagen".getBytes(StandardCharsets.UTF_8),
                        "falso.png", "image/png", "127.0.0.1");

        java.util.concurrent.ExecutionException fallo =
                org.junit.jupiter.api.Assertions.assertThrows(
                        java.util.concurrent.ExecutionException.class, futuro::get);
        assertTrue(fallo.getCause().getMessage().contains("PNG/JPEG"));
    }

    @Test
    void logDeEventosPersisteYNotifica() {
        InterfazAlmacenarInformacion almacenar = mock(InterfazAlmacenarInformacion.class);
        when(almacenar.registrarAccion(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(42L);
        InterfazLogDeEventos log = new LogDeEventos(almacenar);
        List<String> lineas = new ArrayList<>();
        log.suscribir(lineas::add);

        long id = log.registrar(TipoAccion.MENSAJE_TEXTO, "texto de prueba",
                "A001", "127.0.0.1", "{}");

        assertEquals(42L, id);
        assertEquals(1, lineas.size());
        assertTrue(lineas.get(0).contains("MENSAJE_TEXTO"));
        verify(almacenar).registrarAccion(eq(TipoAccion.MENSAJE_TEXTO), eq("texto de prueba"),
                eq("A001"), eq("127.0.0.1"), eq("{}"));
    }
}
