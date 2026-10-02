package universidad.mensajeria.server.vistaconsola;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import universidad.mensajeria.common.interno.EstadoServidor;
import universidad.mensajeria.common.interno.InformeDTO;
import universidad.mensajeria.common.interno.InformeFiltroDTO;
import universidad.mensajeria.common.interno.UsuarioResumen;
import universidad.mensajeria.server.transversal.fachada.Fachada;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Consola administrativa: su unico limite de aplicacion es Fachada. */
public class VistaConsola {

    private static final Logger LOG = LoggerFactory.getLogger(VistaConsola.class);
    private static final int FILAS_POR_PAGINA = 20;

    private final Fachada fachada;
    private volatile LineReader lector;
    private volatile Terminal terminal;
    /** Ultima vista consultada (se redibuja sola ante eventos si autoRefresco). */
    private volatile java.util.function.Supplier<java.util.List<String>> vistaActiva;
    private volatile boolean autoRefresco = true;

    public VistaConsola(Fachada fachada) {
        this.fachada = fachada;
    }

    public void ejecutar() {
        try (Terminal terminalLocal = TerminalBuilder.builder().system(true).dumb(true).build()) {
            terminal = terminalLocal;
            lector = LineReaderBuilder.builder().terminal(terminalLocal).appName("mensajeria-servidor").build();
            cabecera();
            fachada.suscribirEventos(this::imprimirEvento);
            menu();
            while (true) {
                String linea;
                try {
                    linea = lector.readLine("servidor> ");
                } catch (UserInterruptException e) {
                    continue;
                } catch (EndOfFileException e) {
                    break;
                }
                if (!procesar(linea.trim())) break;
                menu();
            }
        } catch (IOException e) {
            LOG.warn("Terminal interactivo no disponible, consola en modo texto: {}", e.getMessage());
            ejecutarSinTerminal();
        } finally {
            lector = null;
            terminal = null;
        }
        System.out.println("Vista Consola finalizada.");
    }

    private void ejecutarSinTerminal() {
        fachada.suscribirEventos(this::imprimirEvento);
        cabecera();
        menu();
        try (var entrada = new java.io.BufferedReader(new java.io.InputStreamReader(System.in))) {
            String linea;
            while ((linea = entrada.readLine()) != null) {
                if (!procesar(linea.trim())) return;
                menu();
            }
        } catch (IOException e) {
            LOG.warn("Entrada estandar no disponible: {}", e.getMessage());
        }
    }

    private boolean procesar(String comando) {
        String normalizado = comando.toLowerCase(Locale.ROOT);
        if (normalizado.equals("informe") || normalizado.startsWith("informe ")) {
            String tipo = normalizado.substring("informe".length()).trim();
            if (tipo.isEmpty()) tipo = pedir("Tipo de informe (usuarios/conexiones/mensajes/auditoria): ");
            informe(tipo);
            return true;
        }
        if (normalizado.equals("difundir") || normalizado.equals("5")) {
            difundir();
            return true;
        }
        if (normalizado.equals("cerrar") || normalizado.startsWith("cerrar ")) {
            String resto = normalizado.substring("cerrar".length()).trim();
            if (resto.isEmpty()) resto = pedir("Cerrar (codigo, id de sesion o *): ");
            cerrar(resto);
            return true;
        }
        if (normalizado.equals("auto") || normalizado.startsWith("auto ")) {
            String resto = normalizado.substring("auto".length()).trim();
            auto(resto);
            return true;
        }
        switch (normalizado) {
            case "", "menu" -> { }
            case "ayuda", "help", "0" -> ayuda();
            case "1", "estado" -> estado();
            case "2", "conectados" -> conectados();
            case "3", "usuarios" -> usuarios();
            case "4", "cola" -> cola();
            case "ip", "6" -> System.out.println("IP local: " + fachada.ipLocal());
            case "iniciar", "7" -> iniciar();
            case "detener", "8" -> fachada.detenerServidor();
            case "9" -> informe(pedir("Tipo de informe (usuarios/conexiones/mensajes/auditoria): "));
            case "salir", "exit", "q", "10" -> { return false; }
            case "11" -> {
                cerrar(pedir("Cerrar (codigo, id de sesion o *): "));
            }
            default -> System.out.println("Opcion no valida: " + comando + " (usa 'ayuda')");
        }
        return true;
    }

    void informe(String tipo) {
        List<String> lineas = renderInforme(tipo);
        paginar(lineas);
        String pedido = tipo;
        vistaActiva = () -> renderInforme(pedido);
    }

    private List<String> renderInforme(String tipo) {
        InformeDTO informe;
        try {
            informe = switch (tipo.toLowerCase(Locale.ROOT)) {
                case "usuarios" -> fachada.informeUsuarios(InformeFiltroDTO.sinFiltro());
                case "conexiones" -> fachada.informeConexiones(InformeFiltroDTO.sinFiltro());
                case "mensajes" -> fachada.informeMensajes(InformeFiltroDTO.sinFiltro());
                case "auditoria" -> fachada.informeAuditoria(InformeFiltroDTO.sinFiltro());
                default -> null;
            };
        } catch (RuntimeException e) {
            return List.of("No se pudo generar el informe: " + e.getMessage());
        }
        if (informe == null) {
            return List.of("Informe desconocido: " + tipo
                    + " (usuarios|conexiones|mensajes|auditoria)");
        }
        return Arrays.asList(TablaAscii.convertir(informe).split("\\R", -1));
    }

    private void cabecera() {
        EstadoServidor e = fachada.estadoServidor();
        System.out.println("=== Mensajeria Academica | TCP " + e.puerto() + " | "
                + (e.activo() ? "ACTIVO" : "DETENIDO") + " ===");
    }

    private void menu() {
        System.out.println("------------------------------------------------------------");
        System.out.println(" 1 Estado        2 Conectados      3 Usuarios       4 Cola");
        System.out.println(" 5 Difundir      6 IP local        7 Iniciar        8 Detener");
        System.out.println(" 9 Informe       0 Ayuda           10 Salir        11 Cerrar");
        System.out.println("------------------------------------------------------------");
    }

    private void ayuda() {
        System.out.println("Opciones: 1 estado, 2 conectados, 3 usuarios, 4 cola, "
                + "5 difundir, 6 ip, 7 iniciar, 8 detener, 9 informe, 10 salir, 11 cerrar. "
                + "Tambien admite: estado, usuarios, conectados, cola, informe <tipo>, difundir, "
                + "cerrar <codigo|id-sesion|*> [motivo], auto <on|off>.");
    }

    private void estado() {
        List<String> lineas = lineasEstado();
        lineas.forEach(System.out::println);
        vistaActiva = this::lineasEstado;
    }

    private List<String> lineasEstado() {
        EstadoServidor e = fachada.estadoServidor();
        return List.of(
                (e.activo() ? "Servidor ACTIVO" : "Servidor DETENIDO")
                        + " | puerto " + e.puerto(),
                "Usuarios conectados: " + e.usuariosConectados() + "/" + e.maxConexiones()
                        + " | sesiones: " + e.sesionesActivas(),
                "Pool: " + e.trabajadoresOcupados() + " ocupados, "
                        + e.trabajadoresDisponibles() + " disponibles, "
                        + e.trabajadoresTotal() + " total",
                "Mensajes procesados: " + e.mensajesProcesados()
                        + " | en cola: " + e.mensajesEnCola());
    }

    private void conectados() {
        List<String> lista = fachada.usuariosConectados();
        if (lista.isEmpty()) {
            System.out.println("No hay usuarios conectados.");
        } else {
            System.out.println("Conectados (" + lista.size() + "): ");
            paginar(lista);
        }
        vistaActiva = () -> {
            List<String> actual = fachada.usuariosConectados();
            return actual.isEmpty() ? List.of("No hay usuarios conectados.")
                    : actual;
        };
    }

    private void usuarios() {
        List<String> filas = filasUsuarios();
        System.out.println("Usuarios registrados (" + filas.size() + "):");
        paginar(filas);
        vistaActiva = () -> {
            List<String> actual = filasUsuarios();
            List<String> salida = new java.util.ArrayList<>();
            salida.add("Usuarios registrados (" + actual.size() + "):");
            salida.addAll(actual);
            return salida;
        };
    }

    private List<String> filasUsuarios() {
        List<UsuarioResumen> usuarios = fachada.usuariosRegistrados();
        return usuarios.stream()
                .map(u -> "%-12s %-22s %-22s %-32s %s".formatted(
                        u.codigo(), u.nombres(), u.apellidos(), u.programa(),
                        u.conectado() ? "CONECTADO" : ""))
                .toList();
    }

    private void cola() {
        System.out.println("Mensajes en cola de procesamiento: " + fachada.mensajesEnCola());
        vistaActiva = () -> List.of(
                "Mensajes en cola de procesamiento: " + fachada.mensajesEnCola());
    }

    /** Cierre administrativo de cualquier sesion (req. #10): codigo, id o "*". */
    void cerrar(String argumentos) {
        String objetivo = argumentos;
        String motivo = "";
        int espacio = argumentos.indexOf(' ');
        if (espacio >= 0) {
            objetivo = argumentos.substring(0, espacio).trim();
            motivo = argumentos.substring(espacio + 1).trim();
        }
        if (objetivo.isEmpty()) {
            System.out.println("Uso: cerrar <codigo|id-sesion|*> [motivo]");
            return;
        }
        try {
            int cerradas = fachada.cerrarConexionAdmin(objetivo, motivo);
            System.out.println("Cierre administrativo de " + objetivo + ": "
                    + cerradas + " sesiones cerradas.");
        } catch (RuntimeException e) {
            System.out.println("No se pudo cerrar: " + e.getMessage());
        }
    }

    void auto(String argumento) {
        if (argumento.equalsIgnoreCase("off") || argumento.equalsIgnoreCase("0")) {
            autoRefresco = false;
            System.out.println("Auto-refresco de la vista: apagado.");
        } else {
            autoRefresco = true;
            System.out.println("Auto-refresco de la vista: encendido.");
        }
    }

    private void difundir() {
        String contenido = pedir("Mensaje para toda la comunidad: ");
        try {
            fachada.difundirAdministrativo(contenido);
            System.out.println("Difusion encolada.");
        } catch (RuntimeException e) {
            System.out.println("No se pudo difundir: " + e.getMessage());
        }
    }

    private void iniciar() {
        try {
            fachada.iniciarServidor();
            System.out.println("Servidor iniciado en el puerto " + fachada.estadoServidor().puerto());
        } catch (RuntimeException e) {
            System.out.println("No se pudo iniciar: " + e.getMessage());
        }
    }

    private String pedir(String prompt) {
        LineReader actual = lector;
        if (actual == null) return "";
        try {
            return actual.readLine(prompt).trim();
        } catch (UserInterruptException | EndOfFileException e) {
            return "";
        }
    }

    private void paginar(List<String> lineas) {
        LineReader actual = lector;
        for (int inicio = 0; inicio < lineas.size(); inicio += FILAS_POR_PAGINA) {
            int fin = Math.min(inicio + FILAS_POR_PAGINA, lineas.size());
            lineas.subList(inicio, fin).forEach(System.out::println);
            if (fin < lineas.size() && actual != null) {
                String seguir = pedir("-- Enter: siguiente pagina; q: terminar -- ");
                if (seguir.equalsIgnoreCase("q")) return;
            }
        }
    }

    private void imprimirEvento(String linea) {
        LineReader actual = lector;
        if (actual != null) {
            actual.printAbove(estilizar(linea));
            // RF-S41: la ultima vista se redibuja sola ante eventos (sin corromper
            // la linea de entrada gracias a printAbove). Se apaga con "auto off".
            if (autoRefresco && vistaActiva != null) {
                try {
                    for (String vista : vistaActiva.get()) {
                        actual.printAbove("  " + vista);
                    }
                } catch (RuntimeException e) {
                    LOG.debug("No se pudo redibujar la vista: {}", e.getMessage());
                }
            }
        } else {
            System.out.println("[INFO] " + linea);
        }
    }

    private String estilizar(String linea) {
        String mayusculas = linea.toUpperCase(Locale.ROOT);
        String categoria = mayusculas.contains("ERROR") || mayusculas.contains("FALLO") ? "ERROR"
                : mayusculas.contains("WARN") || mayusculas.contains("RECHAZ") ? "WARN" : "INFO";
        Terminal actual = terminal;
        if (actual == null || actual.getType() == null || actual.getType().equalsIgnoreCase("dumb")
                || System.getenv("NO_COLOR") != null) return "[" + categoria + "] " + linea;
        String color = categoria.equals("ERROR") ? "31" : categoria.equals("WARN") ? "33"
                : mayusculas.contains("DESCONECT") ? "90"
                : mayusculas.contains("CONECT") ? "32" : "36";
        return "\u001B[" + color + "m[" + categoria + "] " + linea + "\u001B[0m";
    }
}
