package universidad.mensajeria.servicios.impl;

import universidad.mensajeria.common.interno.AuditoriaInformeDTO;
import universidad.mensajeria.common.interno.ConexionInformeDTO;
import universidad.mensajeria.common.interno.InformeDTO;
import universidad.mensajeria.common.interno.InformeFiltroDTO;
import universidad.mensajeria.common.interno.LimitesDTO;
import universidad.mensajeria.common.interno.MensajeResumenDTO;
import universidad.mensajeria.common.interno.UsuarioInformeDTO;
import universidad.mensajeria.servicios.InterfazServiciosDisponibles;
import universidad.mensajeria.servicios.ProveedorServiciosDisponibles;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reglas puras (PLAN2 §2.5 Regla 6): limites por config sobre conteos que
 * entrega la Fachada. Sin red, sin config, sin BD.
 * FASE 9 (§8): arma los 4 informes (filtra + formatea) sobre los DTOs crudos
 * que la Fachada reuno; jamas toca persistencia.
 */
public final class Servicios implements InterfazServiciosDisponibles {

    private static final Pattern RUTA_ETAPA =
            Pattern.compile("\"ruta\"\\s*:\\s*\"([^\"]+)\"");

    private static final Pattern MS_ETAPA =
            Pattern.compile("\"ms\"\\s*:\\s*(\\d+)");

    @Override
    public boolean puedeIniciarSesion(String codigo, int sesionesDelCodigo, int sesionesTotales,
                                      LimitesDTO limites) {
        if (limites.maxConexiones() > 0 && sesionesTotales >= limites.maxConexiones()) {
            return false;
        }
        return limites.maxConexionesPorUsuario() <= 0
                || sesionesDelCodigo < limites.maxConexionesPorUsuario();
    }

    @Override
    public int maxConexiones(LimitesDTO limites) {
        return limites.maxConexiones();
    }

    @Override
    public int maxConexionesPorUsuario(LimitesDTO limites) {
        return limites.maxConexionesPorUsuario();
    }

    @Override
    public void validarTamanoArchivo(long tamanoBytes, long maximoBytes) {
        if (tamanoBytes > maximoBytes) {
            throw new IllegalArgumentException(
                    "el archivo supera el maximo permitido de " + maximoBytes + " bytes");
        }
    }

    // ------------------------------------------------- FASE 9: informes (§8)

    @Override
    public InformeDTO armarInformeUsuarios(List<UsuarioInformeDTO> usuarios,
                                           InformeFiltroDTO filtro) {
        List<List<String>> filas = filtrarPorCodigo(usuarios, UsuarioInformeDTO::codigo, filtro)
                .stream()
                .sorted(Comparator.comparing(UsuarioInformeDTO::codigo))
                .map(u -> List.of(txt(u.codigo()), txt(u.nombres()), txt(u.apellidos()),
                        txt(u.programa()), txt(u.fechaRegistro()), txt(u.fechaUltimaConexion()),
                        u.conectado() ? "Si" : "No"))
                .map(List::copyOf)
                .toList();
        return new InformeDTO("Informe 1 — Directorio de usuarios registrados",
                List.of("Codigo", "Nombres", "Apellidos", "Programa",
                        "Registro", "Ultima conexion", "Conectado"),
                filas);
    }

    @Override
    public InformeDTO armarInformeConexiones(List<ConexionInformeDTO> conexiones,
                                             InformeFiltroDTO filtro) {
        List<List<String>> filas = filtrarPorCodigo(conexiones, ConexionInformeDTO::codigo, filtro)
                .stream()
                .sorted(Comparator.comparingLong(ConexionInformeDTO::vecesConectado).reversed()
                        .thenComparing(ConexionInformeDTO::codigo))
                .map(c -> List.of(persona(c.nombres(), c.apellidos(), c.codigo()),
                        String.valueOf(c.vecesConectado()),
                        String.valueOf(c.sesionesVivas()), txt(c.fechaUltimaConexion())))
                .map(List::copyOf)
                .toList();
        return new InformeDTO("Informe 2 — Conectados y frecuencia de acceso",
                List.of("Usuario [código]", "Veces conectado", "Sesiones vivas", "Ultima conexion"),
                filas);
    }

    @Override
    public InformeDTO armarInformeMensajes(List<MensajeResumenDTO> mensajes,
                                           InformeFiltroDTO filtro) {
        List<List<String>> filas = mensajes.stream()
                .filter(m -> coincide(m.remitente(), filtro) || coincide(m.destinatario(), filtro))
                .sorted(Comparator.comparing(MensajeResumenDTO::fechaEnvio,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingLong(MensajeResumenDTO::id))
                .map(m -> List.of(String.valueOf(m.id()), txt(m.fechaEnvio()),
                        persona(m.remitenteNombres(), m.remitenteApellidos(), m.remitente()),
                        persona(m.destinatarioNombres(), m.destinatarioApellidos(), m.destinatario()),
                        txt(m.tipo()),
                        detalleDe(m), txt(m.hashSha256())))
                .map(List::copyOf)
                .toList();
        return new InformeDTO("Informe 3 — Historico cronologico de mensajes",
                List.of("Id", "Fecha", "Remitente [código]", "Destinatario [código]", "Tipo",
                        "Detalle", "Hash SHA-256"),
                filas);
    }

    @Override
    public InformeDTO armarInformeAuditoria(List<AuditoriaInformeDTO> acciones,
                                            InformeFiltroDTO filtro) {
        List<List<String>> filas = acciones.stream()
                .filter(a -> codigoVacio(filtro)
                        || a.usuario() != null
                        && a.usuario().equalsIgnoreCase(codigo(filtro)))
                .map(a -> List.of(String.valueOf(a.id()), txt(a.fecha()), txt(a.tipo()),
                        persona(a.nombres(), a.apellidos(), a.usuario()),
                        txt(a.ip()), txt(a.descripcion())))
                .map(List::copyOf)
                .toList();
        return new InformeDTO("Informe 4 — Bitacora de auditoria",
                List.of("Id", "Fecha", "Tipo", "Usuario [código]", "IP", "Descripcion"),
                filas);
    }

    // ------------------------------------------------------------- util puro

    /** Metricas de texto o rutas + ms por etapa de las derivadas (§8 Informe 3). */
    private static String detalleDe(MensajeResumenDTO m) {
        String tipo = m.tipo() == null ? "" : m.tipo();
        if ("TEXTO".equalsIgnoreCase(tipo)) {
            return "%s car. · %s pal.".formatted(
                    m.numCaracteres() == null ? "?" : m.numCaracteres(),
                    m.numPalabras() == null ? "?" : m.numPalabras());
        }
        String nombre = txt(m.nombreArchivo());
        List<String> etapas = etapasDe(m.etapasFiltrado());
        return etapas.isEmpty() ? nombre : nombre + " → " + String.join(", ", etapas);
    }

    /** Extrae ruta (ultimo segmento) y ms de cada etapa del JSON (sin dependencias). */
    private static List<String> etapasDe(String etapasJson) {
        if (etapasJson == null || etapasJson.isBlank()) {
            return List.of();
        }
        List<String> partes = new ArrayList<>();
        for (String bloque : etapasJson.split("}")) {
            Matcher rutaBuscador = RUTA_ETAPA.matcher(bloque);
            if (!rutaBuscador.find()) {
                continue;
            }
            String ruta = rutaBuscador.group(1).replace('\\', '/');
            int ultimaBarra = ruta.lastIndexOf('/');
            String simple = ultimaBarra >= 0 ? ruta.substring(ultimaBarra + 1) : ruta;
            Matcher msBuscador = MS_ETAPA.matcher(bloque);
            partes.add(msBuscador.find()
                    ? simple + " (" + msBuscador.group(1) + " ms)"
                    : simple);
        }
        return partes;
    }

    private static <T> List<T> filtrarPorCodigo(List<T> filas, java.util.function.Function<T, String> codigo,
                                                InformeFiltroDTO filtro) {
        if (codigoVacio(filtro)) {
            return filas;
        }
        String buscado = codigo(filtro);
        return filas.stream()
                .filter(fila -> {
                    String valor = codigo.apply(fila);
                    return valor != null && valor.equalsIgnoreCase(buscado);
                })
                .toList();
    }

    private static boolean codigoVacio(InformeFiltroDTO filtro) {
        return codigo(filtro).isEmpty();
    }

    /** true si no hay filtro de codigo o el valor coincide (ignorando mayusculas). */
    private static boolean coincide(String valor, InformeFiltroDTO filtro) {
        return codigoVacio(filtro)
                || valor != null && valor.equalsIgnoreCase(codigo(filtro));
    }

    private static String codigo(InformeFiltroDTO filtro) {
        return filtro == null || filtro.codigo() == null ? "" : filtro.codigo().trim();
    }

    private static String txt(String valor) {
        return valor == null || valor.isBlank() ? "-" : valor;
    }

    private static String persona(String nombres, String apellidos, String codigo) {
        String nombre = ((nombres == null ? "" : nombres.trim()) + " "
                + (apellidos == null ? "" : apellidos.trim())).trim();
        if (nombre.isBlank()) {
            return txt(codigo);
        }
        return codigo == null || codigo.isBlank() ? nombre : nombre + " [" + codigo + "]";
    }

    /** Registro SPI (PLAN2 §2.4). */
    public static final class Proveedor implements ProveedorServiciosDisponibles {

        @Override
        public InterfazServiciosDisponibles crear() {
            return new Servicios();
        }
    }
}
