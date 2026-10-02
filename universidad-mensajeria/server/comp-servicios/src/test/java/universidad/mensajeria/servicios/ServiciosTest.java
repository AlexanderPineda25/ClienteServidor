package universidad.mensajeria.servicios;

import org.junit.jupiter.api.Test;
import universidad.mensajeria.common.interno.AuditoriaInformeDTO;
import universidad.mensajeria.common.interno.ConexionInformeDTO;
import universidad.mensajeria.common.interno.InformeDTO;
import universidad.mensajeria.common.interno.InformeFiltroDTO;
import universidad.mensajeria.common.interno.LimitesDTO;
import universidad.mensajeria.common.interno.MensajeResumenDTO;
import universidad.mensajeria.common.interno.UsuarioInformeDTO;
import universidad.mensajeria.servicios.impl.Servicios;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reglas puras de limites y armado de los 4 informes (sin red, sin BD). */
class ServiciosTest {

    private final InterfazServiciosDisponibles servicios = new Servicios();

    @Test
    void rechazaPorUsuarioYPorTotal() {
        LimitesDTO limites = new LimitesDTO(100, 2, 10_485_760);

        assertFalse(servicios.puedeIniciarSesion("A001", 2, 10, limites));
        assertTrue(servicios.puedeIniciarSesion("B002", 0, 10, limites));
        assertFalse(servicios.puedeIniciarSesion("C003", 0, 100, limites));
        assertEquals(100, servicios.maxConexiones(limites));
        assertEquals(2, servicios.maxConexionesPorUsuario(limites));
    }

    @Test
    void ceroSignificaSinLimite() {
        LimitesDTO libres = new LimitesDTO(0, 0, 0);

        assertTrue(servicios.puedeIniciarSesion("A001", 99, 999, libres));
    }

    @Test
    void validarTamanoArchivo() {
        servicios.validarTamanoArchivo(10, 100);
        assertThrows(IllegalArgumentException.class,
                () -> servicios.validarTamanoArchivo(101, 100));
    }

    // ------------------------------------------------ FASE 9: informes (§8)

    @Test
    void informeUsuariosFiltraPorCodigoYOrdenaAsc() {
        List<UsuarioInformeDTO> usuarios = List.of(
                new UsuarioInformeDTO("B002", "Bea", "Bermudez", "Sistemas",
                        "2026-01-02T08:00:00", null, true),
                new UsuarioInformeDTO("A001", "Ana", "Arias", null,
                        "2026-01-01T08:00:00", "2026-02-01T09:30:00", false));

        InformeDTO todo = servicios.armarInformeUsuarios(usuarios, InformeFiltroDTO.sinFiltro());
        assertEquals(List.of("Codigo", "Nombres", "Apellidos", "Programa",
                "Registro", "Ultima conexion", "Conectado"), todo.columnas());
        assertEquals(List.of("A001", "B002"),
                todo.filas().stream().map(f -> f.get(0)).toList(), "orden por codigo");
        assertEquals("-", todo.filas().get(0).get(3), "null en blanco -> -");
        assertEquals("No", todo.filas().get(0).get(6));
        assertEquals("Si", todo.filas().get(1).get(6));

        InformeDTO filtrado = servicios.armarInformeUsuarios(usuarios,
                new InformeFiltroDTO(null, null, "a001"));
        assertEquals(1, filtrado.filas().size(), "codigo ignorando mayusculas");
        assertEquals("A001", filtrado.filas().get(0).get(0));
    }

    @Test
    void informeConexionesOrdenaPorVecesConectadoDesc() {
        List<ConexionInformeDTO> conexiones = List.of(
                new ConexionInformeDTO("A001", 3, 1, "2026-02-01T09:30:00", "Ana", "Arias"),
                new ConexionInformeDTO("B002", 7, 0, null),
                new ConexionInformeDTO("C003", 3, 0, null));

        InformeDTO informe = servicios.armarInformeConexiones(conexiones,
                InformeFiltroDTO.sinFiltro());
        assertEquals(List.of("Usuario [código]", "Veces conectado", "Sesiones vivas",
                "Ultima conexion"), informe.columnas());
        assertEquals(List.of("B002", "Ana Arias [A001]", "C003"),
                informe.filas().stream().map(f -> f.get(0)).toList());
        assertEquals("7", informe.filas().get(0).get(1));
        assertEquals("-", informe.filas().get(0).get(3), "fecha nula -> -");
    }

    @Test
    void informeMensajesDetallaTextoRutasYFiltraPorCodigo() {
        MensajeResumenDTO texto = new MensajeResumenDTO(1, "A001", "B002", "TEXTO",
                "hola mundo", "hash1", 10, 2, null, null, null, null, null,
                "2026-02-01T10:00:00", "Ana", "Arias", "Luis", "Rojas");
        MensajeResumenDTO imagen = new MensajeResumenDTO(2, "B002", "A001", "IMAGEN",
                null, "hash2", null, null, "foto.png", 99L, 7L, "image/png",
                "[{\"etapa\":\"filtrado\",\"ms\":12,\"ruta\":\"work/1/01_grises.png\"},"
                        + "{\"etapa\":\"reduccion\",\"ms\":8,"
                        + "\"ruta\":\"uploads/hash2/05_reduccion.png\"}]",
                "2026-02-01T11:00:00");

        InformeDTO informe = servicios.armarInformeMensajes(List.of(texto, imagen),
                InformeFiltroDTO.sinFiltro());
        assertEquals(2, informe.filas().size());
        List<String> filaTexto = informe.filas().get(0);
        assertEquals("Ana Arias [A001]", filaTexto.get(2));
        assertEquals("Luis Rojas [B002]", filaTexto.get(3));
        assertEquals("10 car. · 2 pal.", filaTexto.get(5), "detalle de TEXTO");
        List<String> filaImagen = informe.filas().get(1);
        assertEquals("foto.png → 01_grises.png (12 ms), 05_reduccion.png (8 ms)",
                filaImagen.get(5), "rutas + ms por etapa del JSON");

        InformeDTO soloA = servicios.armarInformeMensajes(List.of(texto, imagen),
                new InformeFiltroDTO(null, null, "A001"));
        assertEquals(2, soloA.filas().size(), "A001 es remitente o destinatario");

        InformeDTO soloB = servicios.armarInformeMensajes(List.of(texto),
                new InformeFiltroDTO(null, null, "B002"));
        assertEquals(1, soloB.filas().size());
    }

    @Test
    void informeAuditoriaFiltraPorUsuarioIgnorandoMayusculas() {
        List<AuditoriaInformeDTO> acciones = List.of(
                new AuditoriaInformeDTO(1, "LOGIN", "A001", "inicio de sesion",
                        "2026-02-01T08:00:00", "127.0.0.1", "Ana", "Arias"),
                new AuditoriaInformeDTO(2, "REGISTRO", "B002", "usuario nuevo",
                        "2026-02-01T09:00:00", "127.0.0.1"),
                new AuditoriaInformeDTO(3, "SERVIDOR", null, "servidor iniciado",
                        "2026-02-01T07:00:00", null));

        InformeDTO todo = servicios.armarInformeAuditoria(acciones,
                InformeFiltroDTO.sinFiltro());
        assertEquals(3, todo.filas().size(), "sin filtro: todo (incluso sin usuario)");
        assertEquals("-", todo.filas().get(2).get(3), "usuario nulo -> -");
        assertEquals("Ana Arias [A001]", todo.filas().get(0).get(3));

        InformeDTO soloA = servicios.armarInformeAuditoria(acciones,
                new InformeFiltroDTO(null, null, "a001"));
        assertEquals(1, soloA.filas().size());
        assertEquals("LOGIN", soloA.filas().get(0).get(2));
    }

    @Test
    void informesAceptanFiltroNulo() {
        InformeDTO usuarios = servicios.armarInformeUsuarios(List.of(
                new UsuarioInformeDTO("A001", "Ana", "Arias", "Sistemas",
                        null, null, false)), null);
        assertEquals(1, usuarios.filas().size());
        assertEquals("Informe 1 — Directorio de usuarios registrados", usuarios.titulo());
        assertEquals("Informe_1_Directorio_de_usuarios_registrados",
                usuarios.nombreArchivoCsv());
    }
}
