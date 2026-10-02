package universidad.mensajeria.server.vistaconsola;

import org.junit.jupiter.api.Test;
import universidad.mensajeria.common.interno.InformeDTO;
import universidad.mensajeria.common.interno.InformeFiltroDTO;
import universidad.mensajeria.server.transversal.fachada.Fachada;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Comando "informe" de la CLI (FASE 9, §8): llama a Fachada con sinFiltro()
 * y pinta la tabla ASCII. Sin red, sin BD (Fachada simulada con Mockito).
 */
class VistaConsolaTest {

    private final Fachada fachada = mock(Fachada.class);
    private final VistaConsola vista = new VistaConsola(fachada);

    @Test
    void comandoInformeUsuariosPideInformeSinFiltro() {
        InformeDTO informe = new InformeDTO("Informe 1 — Prueba",
                List.of("Codigo"), List.of(List.of("A001")));
        when(fachada.informeUsuarios(any())).thenReturn(informe);

        String salida = capturar(() -> vista.informe("usuarios"));

        verify(fachada).informeUsuarios(InformeFiltroDTO.sinFiltro());
        assertTrue(salida.contains("Informe 1 — Prueba"));
        assertTrue(salida.contains("| Codigo |"));
        assertTrue(salida.contains("1 fila"));
    }

    @Test
    void comandoSinTipoNiDesconocidoSaleLaAyudaDelComando() {
        String salida = capturar(() -> vista.informe("otra"));

        assertTrue(salida.contains("Informe desconocido: otra"));
        assertTrue(salida.contains("usuarios|conexiones|mensajes|auditoria"));
    }

    @Test
    void errorDeLaFachadaSeReportaSinRomperLaConsola() {
        when(fachada.informeMensajes(any())).thenThrow(new IllegalStateException("bd caida"));

        String salida = capturar(() -> vista.informe("mensajes"));

        assertTrue(salida.contains("No se pudo generar el informe: bd caida"));
    }

    @Test
    void comandoCerrarDelegaEnLaFachada() {
        when(fachada.cerrarConexionAdmin("A001", "")).thenReturn(2);

        String salida = capturar(() -> vista.cerrar("A001"));

        verify(fachada).cerrarConexionAdmin("A001", "");
        assertTrue(salida.contains("2 sesiones cerradas"));
    }

    @Test
    void comandoCerrarSinBlancosMuestraUso() {
        String salida = capturar(() -> vista.cerrar(""));

        assertTrue(salida.contains("Uso: cerrar"));
    }

    @Test
    void comandoCerrarSinSesionesReportaElMotivo() {
        when(fachada.cerrarConexionAdmin("ZZZ", "")).thenThrow(new IllegalArgumentException("sin sesiones"));

        String salida = capturar(() -> vista.cerrar("ZZZ"));

        assertTrue(salida.contains("No se pudo cerrar: sin sesiones"));
    }

    @Test
    void comandoAutoConmutaElRefresco() {
        String apagado = capturar(() -> vista.auto("off"));
        assertTrue(apagado.contains("apagado"));
        String encendido = capturar(() -> vista.auto("on"));
        assertTrue(encendido.contains("encendido"));
    }

    private static String capturar(Runnable accion) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            accion.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
