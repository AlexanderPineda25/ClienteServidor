package universidad.mensajeria.cliente.fachada;

import universidad.mensajeria.cliente.componentes.autenticacion.InterfazAutenticacion;
import universidad.mensajeria.cliente.componentes.cache.InterfazCacheUsuarios;
import universidad.mensajeria.cliente.componentes.conexion.InterfazConexionRed;
import universidad.mensajeria.cliente.componentes.conexion.ReceptorMensajes;
import universidad.mensajeria.cliente.componentes.historial.InterfazHistorialLocal;
import universidad.mensajeria.cliente.transversal.records.EstadoMensaje;
import universidad.mensajeria.cliente.transversal.records.MensajeLocal;
import universidad.mensajeria.cliente.transversal.records.PendienteEnvio;
import universidad.mensajeria.cliente.transversal.records.RecienteChat;
import universidad.mensajeria.cliente.transversal.records.UsuarioLocal;
import universidad.mensajeria.cliente.transversal.utilerias.ValidacionCliente;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.common.tipos.TipoMensaje;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Único punto de entrada de la UI (§7.1): inyecta interfaces, nunca
 * implementaciones (DIP + DI por constructor). Orquesta red + historial:
 * lo enviado con red se guarda local (enviado), sin red va a pendientes y
 * lo recibido se persiste para el modo offline. Avisos a la UI vía Observer.
 */
public final class FachadaCliente {

    /** Marcador de destino para broadcasts propios (sin destinatario único). */
    public static final String DESTINO_BROADCAST = "*";

    private static final Logger LOG = Logger.getLogger(FachadaCliente.class.getName());

    private final InterfazConexionRed red;
    private final InterfazAutenticacion autenticacion;
    private final InterfazHistorialLocal historial;
    private final InterfazCacheUsuarios cache;
    private final List<Consumer<Mensaje>> suscriptores = new CopyOnWriteArrayList<>();
    /**
     * FASE 13.5: UN executor acotado para todo el trabajo de fondo del cliente
     * (leídos, reintentos, tareas de la UI). Adiós `new Thread` por evento.
     */
    private final ExecutorService fondo;
    /**
     * FASE 11.1: worker daemon que vacia `pendientes_envio` en orden cada
     * {@link #INTERVALO_FLUSH_SEGUNDOS} s (best-effort, nunca tumba el hilo).
     * Cubre la reconexion silenciosa: si la red vuelve entre dos logins, los
     * pendientes salen solos sin accion de la UI.
     */
    public static final long INTERVALO_FLUSH_SEGUNDOS = 30;
    private volatile ScheduledExecutorService flush;

    public FachadaCliente(InterfazConexionRed red, InterfazAutenticacion autenticacion,
                          InterfazHistorialLocal historial, InterfazCacheUsuarios cache) {
        this.red = red;
        this.autenticacion = autenticacion;
        this.historial = historial;
        this.cache = cache;
        ThreadFactory hilos = new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public Thread newThread(Runnable tarea) {
                Thread hilo = new Thread(tarea, "cliente-fondo-" + n.incrementAndGet());
                hilo.setDaemon(true);
                return hilo;
            }
        };
        this.fondo = Executors.newFixedThreadPool(2, hilos);
        red.suscribir(new ReceptorInterno());
    }

    /** Executor para las `Task` de JavaFX (nunca bloquear el hilo UI). */
    public Executor fondo() {
        return fondo;
    }

    /** Apaga el executor (al salir de la app). */
    public void cerrar() {
        detenerFlushPendientes();
        fondo.shutdownNow();
    }

    /** Arranca el worker de flush (idempotente); periodo en segundos. */
    public synchronized void iniciarFlushPendientes(long periodoSegundos) {
        if (flush != null && !flush.isShutdown()) {
            return;
        }
        long periodo = Math.max(5, periodoSegundos);
        ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(tarea -> {
            Thread hilo = new Thread(tarea, "cliente-flush");
            hilo.setDaemon(true);
            return hilo;
        });
        worker.scheduleWithFixedDelay(() -> {
            try {
                reintentarPendientes();
            } catch (Exception e) {
                LOG.fine(() -> "[flush] reintento omitido: " + e.getMessage());
            }
        }, periodo, periodo, TimeUnit.SECONDS);
        flush = worker;
    }

    /** Detiene el worker de flush (idempotente). */
    public synchronized void detenerFlushPendientes() {
        if (flush != null) {
            flush.shutdownNow();
            flush = null;
        }
    }

    /** La UI se suscribe a mensajes entrantes (ya persistidos). */
    public void suscribir(Consumer<Mensaje> observador) {
        // FASE 13.16: sin duplicados (un re-login sin detener sumaba fantasmas).
        if (!suscriptores.contains(observador)) {
            suscriptores.add(observador);
        }
    }

    /** FASE 13.10: quita un suscriptor (fin de los refrescos fantasma). */
    public void desuscribir(Consumer<Mensaje> observador) {
        suscriptores.remove(observador);
    }

    /** FASE 13.17: cuántos observadores hay (diagnóstico de fantasmas). */
    public int suscriptores() {
        return suscriptores.size();
    }

    public void conectar() throws IOException {
        red.conectar();
    }

    public void desconectar() {
        red.desconectar();
    }

    public void cerrarSesionRemota() {
        autenticacion.olvidarSesion();
        red.desconectar();
    }

    public boolean conectado() {
        return red.conectado();
    }

    public boolean login(String codigo, String contrasena) throws Exception {
        historial.prepararCuenta(codigo);
        if (!red.conectado()) {
            red.conectar();
        }
        boolean ok = autenticacion.login(codigo, contrasena);
        if (ok) {
            asegurarEnCache(codigo);
            reintentarPendientes();
            iniciarFlushPendientes(INTERVALO_FLUSH_SEGUNDOS);
        }
        return ok;
    }

    public void logout() throws Exception {
        detenerFlushPendientes();
        autenticacion.logout();
        red.desconectar();
    }

    public String codigoActual() {
        return autenticacion.codigoActual();
    }

    /** Conectados del servidor + refresco de la caché local. */
    public List<String> listarConectados() throws Exception {
        exigirSesion();
        Mensaje respuesta = red.listarConectados();
        List<String> conectados = respuesta.usuariosConectados() != null
                ? respuesta.usuariosConectados() : List.of();
        cache.marcarConectados(conectados);
        for (String codigo : conectados) {
            asegurarEnCache(codigo);
        }
        return conectados;
    }

    /** Directorio local (offline): conectados primero. */
    public List<UsuarioLocal> directorioLocal() throws Exception {
        return cache.listar();
    }

    /** Carga el directorio completo solo al entrar o bajo actualización manual. */
    public List<UsuarioLocal> sincronizarDirectorio() throws Exception {
        exigirSesion();
        Mensaje respuesta = red.listarUsuarios();
        if (respuesta.tipo() != TipoMensaje.LISTAR_USUARIOS_RESPUESTA
                || respuesta.contenido() == null || respuesta.contenido().isBlank()) {
            throw new IllegalStateException("directorio de usuarios no disponible");
        }
        com.google.gson.JsonArray filas;
        try {
            filas = com.google.gson.JsonParser.parseString(respuesta.contenido()).getAsJsonArray();
        } catch (Exception e) {
            throw new IllegalStateException("directorio de usuarios ilegible", e);
        }
        String ahora = LocalDateTime.now().toString();
        List<UsuarioLocal> usuarios = new ArrayList<>();
        boolean incluyeSesion = false;
        for (com.google.gson.JsonElement fila : filas) {
            if (!fila.isJsonObject()) {
                continue;
            }
            com.google.gson.JsonObject u = fila.getAsJsonObject();
            String codigo = texto(u, "codigo");
            if (codigo == null || codigo.isBlank()) {
                continue;
            }
            usuarios.add(new UsuarioLocal(codigo,
                    valorONada(texto(u, "nombres")), valorONada(texto(u, "apellidos")),
                    valorONada(texto(u, "programa")), booleano(u, "conectado"),
                    ahora, ahora));
            incluyeSesion |= codigo.equals(codigoActual());
        }
        if (usuarios.isEmpty() || !incluyeSesion) {
            throw new IllegalStateException("directorio incompleto; se conserva la caché local");
        }
        cache.reemplazarDirectorio(usuarios);
        return cache.listar();
    }

    /**
     * Chat 1-a-1 texto: con red envía y guarda; sin red guarda + pendiente.
     * Devuelve true si salió por red.
     */
    public boolean enviarTexto(String destinatario, String contenido) throws Exception {
        exigirSesion();
        ValidacionCliente.destinatario(destinatario);
        ValidacionCliente.contenido(contenido);
        String id = java.util.UUID.randomUUID().toString();
        String ahora = LocalDateTime.now().toString();
        historial.guardar(new MensajeLocal(id, codigoActual(), destinatario,
                TipoMensaje.MENSAJE_TEXTO.name(), contenido, null, contenido.length(),
                contarPalabras(contenido), null, null, null, ahora, true, true,
                EstadoMensaje.PENDIENTE.name()));
        try {
            Mensaje ack = red.enviarTexto(id, codigoActual(), destinatario, contenido);
            if (!Boolean.TRUE.equals(ack.exito())) {
                throw new IllegalStateException(ack.mensajeError() != null
                        ? ack.mensajeError() : "envio rechazado");
            }
            historial.marcarEstado(id, EstadoMensaje.ENVIADO.name());
            return true;
        } catch (IOException sinRed) {
            historial.marcarEstado(id, EstadoMensaje.PENDIENTE.name());
            historial.registrarPendiente(new PendienteEnvio(id,
                    TipoMensaje.MENSAJE_TEXTO.name(), codigoActual(), destinatario,
                    contenido, null, null, ahora, 0, sinRed.getMessage()));
            return false;
        } catch (RuntimeException rechazo) {
            historial.marcarEstado(id, EstadoMensaje.ERROR.name());
            throw rechazo;
        }
    }

    /** Chat 1-a-1 imagen con preview previa en la UI (bytes ya elegidos). */
    public boolean enviarImagen(String destinatario, byte[] bytes,
                                String nombreArchivo, String mime) throws Exception {
        exigirSesion();
        ValidacionCliente.destinatario(destinatario);
        ValidacionCliente.imagen(bytes);
        String id = java.util.UUID.randomUUID().toString();
        String ahora = LocalDateTime.now().toString();
        String base64 = Base64.getEncoder().encodeToString(bytes);
        historial.guardar(new MensajeLocal(id, codigoActual(), destinatario,
                TipoMensaje.MENSAJE_IMAGEN.name(), base64, null, null, null,
                nombreArchivo, null, (long) bytes.length, ahora, true, true,
                EstadoMensaje.PENDIENTE.name()));
        try {
            Mensaje resultado = red.enviarImagen(id, codigoActual(), destinatario,
                    nombreArchivo, mime, base64);
            if (!Boolean.TRUE.equals(resultado.exito())
                    && resultado.tipo() != TipoMensaje.IMAGE_FILTERED_RESULT) {
                throw new IllegalStateException(resultado.mensajeError() != null
                        ? resultado.mensajeError() : "envio rechazado");
            }
            if (resultado.archivoId() != null) {
                historial.actualizarArchivoId(id, resultado.archivoId());
            }
            historial.marcarEstado(id, EstadoMensaje.ENVIADO.name());
            return true;
        } catch (IOException sinRed) {
            historial.marcarEstado(id, EstadoMensaje.PENDIENTE.name());
            historial.registrarPendiente(new PendienteEnvio(id,
                    TipoMensaje.MENSAJE_IMAGEN.name(), codigoActual(), destinatario,
                    null, nombreArchivo, bytes, ahora, 0, sinRed.getMessage()));
            return false;
        } catch (RuntimeException rechazo) {
            historial.marcarEstado(id, EstadoMensaje.ERROR.name());
            throw rechazo;
        }
    }

    /** Chat 1-a-1 archivo generico (PDF, docs, zip): mismo flujo que imagen. */
    public boolean enviarArchivo(String destinatario, byte[] bytes,
                                 String nombreArchivo, String mime) throws Exception {
        exigirSesion();
        ValidacionCliente.destinatario(destinatario);
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("el archivo esta vacio");
        }
        String id = java.util.UUID.randomUUID().toString();
        String ahora = LocalDateTime.now().toString();
        String base64 = Base64.getEncoder().encodeToString(bytes);
        historial.guardar(new MensajeLocal(id, codigoActual(), destinatario,
                TipoMensaje.MENSAJE_ARCHIVO.name(), base64, null, null, null,
                nombreArchivo, null, (long) bytes.length, ahora, true, true,
                EstadoMensaje.PENDIENTE.name()));
        try {
            Mensaje resultado = red.enviarArchivo(id, codigoActual(), destinatario,
                    nombreArchivo, mime == null ? "application/octet-stream" : mime, base64);
            if (!Boolean.TRUE.equals(resultado.exito())) {
                throw new IllegalStateException(resultado.mensajeError() != null
                        ? resultado.mensajeError() : "envio rechazado");
            }
            if (resultado.archivoId() != null) {
                historial.actualizarArchivoId(id, resultado.archivoId());
            }
            historial.marcarEstado(id, EstadoMensaje.ENVIADO.name());
            return true;
        } catch (IOException sinRed) {
            historial.marcarEstado(id, EstadoMensaje.PENDIENTE.name());
            historial.registrarPendiente(new PendienteEnvio(id,
                    TipoMensaje.MENSAJE_ARCHIVO.name(), codigoActual(), destinatario,
                    null, nombreArchivo, bytes, ahora, 0, sinRed.getMessage()));
            return false;
        } catch (RuntimeException rechazo) {
            historial.marcarEstado(id, EstadoMensaje.ERROR.name());
            throw rechazo;
        }
    }

    /** Broadcast a todos los conectados (req. servidor #9). */
    public void difundir(String contenido) throws Exception {
        exigirSesion();
        ValidacionCliente.contenido(contenido);
        String id = java.util.UUID.randomUUID().toString();
        historial.guardar(new MensajeLocal(id, codigoActual(),
                DESTINO_BROADCAST, TipoMensaje.BROADCAST.name(), contenido, null,
                contenido.length(), contarPalabras(contenido), null, null, null,
                LocalDateTime.now().toString(), true, true, EstadoMensaje.PENDIENTE.name()));
        try {
            Mensaje ack = red.difundir(id, codigoActual(), contenido);
            if (!Boolean.TRUE.equals(ack.exito())) {
                throw new IllegalStateException(ack.mensajeError() != null
                        ? ack.mensajeError() : "difusion rechazada");
            }
            historial.marcarEstado(id, EstadoMensaje.ENVIADO.name());
        } catch (Exception e) {
            historial.marcarEstado(id, EstadoMensaje.ERROR.name());
            throw e;
        }
    }

    /** Recientes con presencia de CACHÉ (nunca red: el push PRESENCIA la mantiene). */
    public List<RecienteChat> recientes() throws Exception {
        exigirSesion();
        long inicio = System.nanoTime();
        List<RecienteChat> base = historial.recientes(codigoActual());
        java.util.Set<String> conectados = new java.util.HashSet<>();
        // FASE 13.14: SOLO caché local. La versión anterior llamaba a red aquí y
        // cada auto-refresco podía colgarse hasta el timeout (15 s) si algo se
        // atoraba; la presencia llega por push (ReceptorInterno) y el refresh
        // manual usa refrescarPresencia().
        for (UsuarioLocal u : cache.listar()) {
            if (u.conectado()) {
                conectados.add(u.codigo());
            }
        }
        List<RecienteChat> salida = new ArrayList<>();
        for (RecienteChat r : base) {
            salida.add(new RecienteChat(r.otro(), r.ultimoContenido(), r.ultimoTipo(),
                    r.fechaUltimo(), r.noLeidos(), conectados.contains(r.otro())));
        }
        salida.sort((a, b) -> {
            if (a.conectado() != b.conectado()) {
                return a.conectado() ? -1 : 1;
            }
            return b.fechaUltimo().compareTo(a.fechaUltimo());
        });
        // Directorio sin conversación también aparece (sin preview).
        for (UsuarioLocal u : cache.listar()) {
            if (u.codigo().equals(codigoActual())) {
                continue;
            }
            boolean ya = salida.stream().anyMatch(r -> r.otro().equals(u.codigo()));
            if (!ya) {
                salida.add(new RecienteChat(u.codigo(), "", "", "", 0,
                        conectados.contains(u.codigo())));
            }
        }
        LOG.fine(() -> "[perf] recientes h2=" + entre(System.nanoTime(), inicio)
                + "ms red=0 (solo cache)"
                + " (" + salida.size() + " chats)");
        return salida;
    }

    /**
     * FASE 13.14: presencia fresca de RED (bloquea hasta 15 s). Solo para login,
     * "Actualizar" manual y arranque: NUNCA en el auto-refresco.
     */
    public List<String> refrescarPresencia() throws Exception {
        return listarConectados();
    }

    /** FASE 13.9: bytes de UN mensaje (miniatura visible), nunca la lista. */
    public java.util.Optional<String> blobMensaje(String idMensaje) throws Exception {
        return historial.blobPorId(idMensaje);
    }

    /** Marca como leídos los entrantes y conserva cada acuse hasta enviarlo. */
    public void marcarLeidos(String otro) throws Exception {
        exigirSesion();
        long inicio = System.nanoTime();
        for (String idMensaje : historial.idsNoLeidos(codigoActual(), otro)) {
            String idPendiente = "lectura:" + idMensaje;
            if (historial.pendientePorId(idPendiente).isEmpty()) {
                historial.registrarPendiente(new PendienteEnvio(idPendiente,
                        TipoMensaje.MENSAJE_LEIDO.name(), codigoActual(), otro,
                        idMensaje, null, null, LocalDateTime.now().toString(), 0, null));
            }
        }
        historial.marcarLeidosDe(codigoActual(), otro);
        reintentarPendientes();
        LOG.fine(() -> "[perf] marcarLeidos=" + entre(System.nanoTime(), inicio) + "ms");
    }

    /** FASE 13.5: versión de fondo (best-effort) para la UI. */
    public void marcarLeidosFondo(String otro) {
        fondo.execute(() -> {
            try {
                marcarLeidos(otro);
            } catch (Exception e) {
                LOG.fine(() -> "[perf] marcarLeidosFondo omitido: " + e.getMessage());
            }
        });
    }

    public void expulsarOtrasSesiones() throws Exception {
        exigirSesion();
        red.expulsar(codigoActual());
    }

    public int sesionesInfo() {
        return -1;
    }

    /** Historial offline paginado (más reciente primero, como el contrato). */
    public List<MensajeLocal> conversacion(String otro, int limite, int offset) throws Exception {
        exigirSesion();
        long inicio = System.nanoTime();
        List<MensajeLocal> pagina = historial.pagina(codigoActual(), otro, limite, offset);
        List<MensajeLocal> cronologica = new ArrayList<>(pagina);
        java.util.Collections.reverse(cronologica);
        LOG.fine(() -> "[perf] conversacion=" + entre(System.nanoTime(), inicio)
                + "ms (" + cronologica.size() + " msgs)");
        return cronologica;
    }

    public int contarConversacion(String otro) throws Exception {
        exigirSesion();
        return historial.contar(codigoActual(), otro);
    }

    /** FASE 6 — Solicitar página del historial remoto al servidor. */
    public Mensaje solicitarHistorialServidor(String destinatario, int pagina) throws Exception {
        exigirSesion();
        return red.solicitarHistorial(codigoActual(), destinatario, pagina);
    }

    /** Resultado de una página remota ya persistida en H2. */
    public record PaginaRemota(int recibidos, int pagina, int totalPaginas) {
    }

    /**
     * FASE 13.12: trae UNA página remota (`HISTORIAL_PAGE`, 50 msg, sin bytes),
     * la persiste (upsert por id) y registra `archivoId` para descargas.
     * Llamar en hilo de fondo: `pedir()` bloquea hasta 15 s.
     */
    public PaginaRemota traerHistorial(String otro, int pagina) throws Exception {
        exigirSesion();
        long inicio = System.nanoTime();
        Mensaje respuesta = red.solicitarHistorial(codigoActual(), otro, pagina);
        if (!Boolean.TRUE.equals(respuesta.exito())) {
            throw new IllegalStateException(respuesta.mensajeError() != null
                    ? respuesta.mensajeError() : "historial rechazado");
        }
        int recibidos = 0;
        String json = respuesta.contenido();
        if (json != null && !json.isBlank()) {
            com.google.gson.JsonArray filas;
            try {
                filas = com.google.gson.JsonParser.parseString(json).getAsJsonArray();
            } catch (Exception e) {
                throw new IllegalStateException("pagina remota ilegible", e);
            }
            for (com.google.gson.JsonElement fila : filas) {
                com.google.gson.JsonObject o = fila.getAsJsonObject();
                String id = texto(o, "id");
                if (id == null) {
                    continue;
                }
                historial.guardar(new MensajeLocal(id,
                        texto(o, "remitente"), texto(o, "destinatario"), texto(o, "tipo"),
                        texto(o, "contenido"), texto(o, "hashSha256"),
                        entero(o, "numCaracteres"), entero(o, "numPalabras"),
                        texto(o, "nombreArchivo"), null, largo(o, "tamanoArchivo"),
                        texto(o, "fechaEnvio") != null ? texto(o, "fechaEnvio")
                                : LocalDateTime.now().toString(),
                        false, false, EstadoMensaje.ENTREGADO.name(), texto(o, "archivoId")));
                recibidos++;
            }
        }
        int total = respuesta.totalPaginas() != null ? respuesta.totalPaginas() : 0;
        LOG.fine("[perf] historialRemoto pag=" + pagina + " recibidos=" + recibidos
                + " en " + entre(System.nanoTime(), inicio) + "ms");
        return new PaginaRemota(recibidos, pagina, total);
    }

    /**
     * FASE 13.13: descarga los bytes de una imagen (`DESCARGAR_ARCHIVO`),
     * los guarda en H2 y devuelve los bytes para la miniatura.
     */
    public byte[] descargarImagen(String idMensaje) throws Exception {
        exigirSesion();
        java.util.Optional<String> local = historial.blobPorId(idMensaje);
        if (local.isPresent() && !local.get().isBlank()) {
            return Base64.getDecoder().decode(local.get());
        }
        String archivoId = historial.archivoIdPorId(idMensaje).orElse(null);
        if (archivoId == null) {
            throw new IllegalStateException(
                    "metadatos de imagen pendientes; actualiza esta página del historial");
        }
        Mensaje respuesta = red.descargarArchivo(archivoId);
        if (!Boolean.TRUE.equals(respuesta.exito()) || respuesta.contenidoImagen() == null) {
            throw new IllegalStateException(respuesta.mensajeError() != null
                    ? respuesta.mensajeError() : "descarga rechazada");
        }
        byte[] bytes = Base64.getDecoder().decode(respuesta.contenidoImagen());
        historial.actualizarContenido(idMensaje,
                Base64.getEncoder().encodeToString(bytes));
        return bytes;
    }

    private static String texto(com.google.gson.JsonObject o, String campo) {
        return o.has(campo) && !o.get(campo).isJsonNull() ? o.get(campo).getAsString() : null;
    }

    private static String valorONada(String valor) {
        return valor == null ? "" : valor;
    }

    private static boolean booleano(com.google.gson.JsonObject o, String campo) {
        try {
            return o.has(campo) && !o.get(campo).isJsonNull() && o.get(campo).getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    private static Integer entero(com.google.gson.JsonObject o, String campo) {
        try {
            return o.has(campo) && !o.get(campo).isJsonNull() ? o.get(campo).getAsInt() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Long largo(com.google.gson.JsonObject o, String campo) {
        try {
            return o.has(campo) && !o.get(campo).isJsonNull() ? o.get(campo).getAsLong() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** FASE 6 — Descargar archivo (imagen) del servidor bajo demanda. */
    public Mensaje descargarArchivo(String archivoId) throws Exception {
        exigirSesion();
        return red.descargarArchivo(archivoId);
    }

    public List<PendienteEnvio> pendientes() throws Exception {
        return historial.pendientes();
    }

    /** Reenvía pendientes en orden; devuelve cuántos salieron. */
    public int reintentarPendientes() throws Exception {
        if (codigoActual() == null || !red.conectado()) {
            return 0;
        }
        int enviados = 0;
        for (PendienteEnvio pendiente : historial.pendientes()) {
            try {
                if (TipoMensaje.MENSAJE_LEIDO.name().equals(pendiente.tipo())) {
                    Mensaje ack = red.enviarLectura(
                            pendiente.origen(), pendiente.destino(), pendiente.contenido());
                    exigirExito(ack, "acuse de lectura rechazado");
                } else if (TipoMensaje.MENSAJE_IMAGEN.name().equals(pendiente.tipo())) {
                    String base64 = pendiente.payload() == null ? ""
                            : Base64.getEncoder().encodeToString(pendiente.payload());
                    Mensaje ack = red.enviarImagen(pendiente.id(), pendiente.origen(), pendiente.destino(),
                            pendiente.nombreArchivo(), "application/octet-stream", base64);
                    exigirExito(ack, "reenvio de imagen rechazado");
                    if (ack.archivoId() != null) {
                        historial.actualizarArchivoId(pendiente.id(), ack.archivoId());
                    }
                    historial.marcarEstado(pendiente.id(), EstadoMensaje.ENVIADO.name());
                } else if (TipoMensaje.MENSAJE_ARCHIVO.name().equals(pendiente.tipo())) {
                    String base64 = pendiente.payload() == null ? ""
                            : Base64.getEncoder().encodeToString(pendiente.payload());
                    Mensaje ack = red.enviarArchivo(pendiente.id(), pendiente.origen(), pendiente.destino(),
                            pendiente.nombreArchivo(), "application/octet-stream", base64);
                    if (!Boolean.TRUE.equals(ack.exito())) {
                        throw new IllegalStateException(ack.mensajeError() != null
                                ? ack.mensajeError() : "reenvio de archivo rechazado");
                    }
                    if (ack.archivoId() != null) {
                        historial.actualizarArchivoId(pendiente.id(), ack.archivoId());
                    }
                    historial.marcarEstado(pendiente.id(), EstadoMensaje.ENVIADO.name());
                } else {
                    Mensaje ack = red.enviarTexto(pendiente.id(), pendiente.origen(),
                            pendiente.destino(), pendiente.contenido());
                    exigirExito(ack, "reenvio de mensaje rechazado");
                    historial.marcarEstado(pendiente.id(), EstadoMensaje.ENVIADO.name());
                }
                historial.eliminarPendiente(pendiente.id());
                enviados++;
            } catch (IllegalStateException rechazo) {
                // Rechazo definitivo del servidor (destinatario inexistente, contenido
                // vacio...): no reintentar eternamente ni bloquear los acuses detras.
                historial.marcarEstado(pendiente.id(), EstadoMensaje.ERROR.name());
                historial.eliminarPendiente(pendiente.id());
                LOG.fine(() -> "[reintento] pendiente descartado por rechazo: "
                        + pendiente.id() + " (" + rechazo.getMessage() + ")");
            } catch (IOException | RuntimeException e) {
                historial.incrementarIntento(pendiente.id(), e.getMessage());
                break;
            }
        }
        return enviados;
    }

    private void exigirSesion() {
        if (codigoActual() == null) {
            throw new IllegalStateException("inicia sesion primero");
        }
    }

    private void asegurarEnCache(String codigo) throws Exception {
        if (cache.porCodigo(codigo).isEmpty()) {
            String ahora = LocalDateTime.now().toString();
            cache.guardar(new UsuarioLocal(codigo, codigo, "", "", false, ahora, ahora));
        }
    }

    private static void exigirExito(Mensaje respuesta, String error) {
        if (!Boolean.TRUE.equals(respuesta.exito())
                && respuesta.tipo() != TipoMensaje.IMAGE_FILTERED_RESULT) {
            throw new IllegalStateException(respuesta.mensajeError() != null
                    ? respuesta.mensajeError() : error);
        }
    }

    private static int contarPalabras(String contenido) {
        return contenido.trim().split("\\s+").length;
    }

    /** FASE 13.6/13.11: milisegundos entre dos `nanoTime`. */
    private static long entre(long finNanos, long inicioNanos) {
        return (finNanos - inicioNanos) / 1_000_000;
    }

    /** Persiste lo entrante y lo reenvía a la UI. */
    private final class ReceptorInterno implements ReceptorMensajes {

        @Override
        public void alRecibir(Mensaje mensaje) {
            try {
                if (mensaje.tipo() == TipoMensaje.MENSAJE_ENTREGADO) {
                    if (mensaje.contenido() != null) {
                        historial.marcarEstado(mensaje.contenido(),
                                EstadoMensaje.ENTREGADO.name());
                    }
                } else if (mensaje.tipo() == TipoMensaje.MENSAJE_LEIDO) {
                    if (mensaje.contenido() != null) {
                        historial.marcarEstado(mensaje.contenido(),
                                EstadoMensaje.LEIDO.name());
                    }
                } else if (mensaje.tipo() == TipoMensaje.PRESENCIA) {
                    if (mensaje.codigo() != null) {
                        asegurarEnCache(mensaje.codigo());
                    }
                    if (mensaje.usuariosConectados() != null) {
                        try {
                            cache.marcarConectados(mensaje.usuariosConectados());
                        } catch (Exception ignorada) {
                            // presencia best-effort
                        }
                    }
                } else if (mensaje.tipo() == TipoMensaje.MENSAJE_TEXTO
                        || mensaje.tipo() == TipoMensaje.MENSAJE_IMAGEN
                        || mensaje.tipo() == TipoMensaje.MENSAJE_ARCHIVO
                        || mensaje.tipo() == TipoMensaje.BROADCAST
                        || mensaje.tipo() == TipoMensaje.SYNC_LOGIN) {
                    // Lo entrante siempre es (remitente → yo): así el broadcast
                    // recibido aparece en la conversación con su remitente.
                    String yo = codigoActual();
                    String destino = yo != null ? yo : mensaje.destinatario();
                    String cuerpo = mensaje.contenido();
                    if ((mensaje.tipo() == TipoMensaje.MENSAJE_IMAGEN
                            || mensaje.tipo() == TipoMensaje.MENSAJE_ARCHIVO)
                            && mensaje.contenidoImagen() != null) {
                        cuerpo = mensaje.contenidoImagen();
                    }
                    historial.guardar(new MensajeLocal(
                            mensaje.id() != null ? mensaje.id() : UUID.randomUUID().toString(),
                            mensaje.remitente(), destino, mensaje.tipo().name(),
                            cuerpo, mensaje.hashSha256(),
                            mensaje.numCaracteres(), mensaje.numPalabras(),
                            mensaje.nombreArchivo(), null, mensaje.tamanoArchivo(),
                            mensaje.fechaHora() != null ? mensaje.fechaHora()
                                    : LocalDateTime.now().toString(),
                            // FASE 13.20: el eco de mi propio mensaje (auto-chat u
                            // otra sesión) conserva enviado=1; si no, mi burbuja
                            // caía al lado equivocado y el tick se perdía.
                            yo != null && yo.equals(mensaje.remitente()), true,
                            EstadoMensaje.ENTREGADO.name(), mensaje.archivoId()));
                    if (mensaje.remitente() != null) {
                        asegurarEnCache(mensaje.remitente());
                    }
                }
            } catch (Exception e) {
                // persistir no puede tumbar al lector: se reporta y sigue con el aviso
            }
            suscriptores.forEach(obs -> obs.accept(mensaje));
        }

        @Override
        public void alCierre(Mensaje aviso) {
            suscriptores.forEach(obs -> obs.accept(aviso));
        }

        @Override
        public void alError(String motivo) {
            // ruido asíncrono: la UI lo ignora (los errores de solicitud van por futuro)
        }

        @Override
        public void alDesconexion() {
            // la UI lo nota por suscriptores? se re-conecta manual: sin-op documentado
        }
    }
}
