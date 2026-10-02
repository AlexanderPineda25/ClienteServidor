package universidad.mensajeria.cliente.componentes.conexion;

import universidad.mensajeria.common.codec.TramaCodec;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.common.tipos.TipoMensaje;

import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Socket TCP del cliente Java (§7.1 red): tramas 4B + JSON vía TramaCodec.
 *
 * Las respuestas del servidor pueden intercalarse con entregas asíncronas
 * (un BROADCAST puede llegar mientras espero mi ACK), así que un hilo lector
 * demultiplexa: tramas con id pendiente completan su futuro; el resto va al
 * {@code ReceptorMensajes}. Escritura sincronizada por socket (§4.3).
 */
public final class ConexionCliente implements InterfazConexionRed {

    private static final int TIMEOUT_RESPUESTA_SEG = 15;

    private final String host;
    private final int puerto;
    private final boolean tlsHabilitado;
    private final int tlsPuerto;
    private final String tlsConfianza;

    private final Object candadoSalida = new Object();
    private final ConcurrentHashMap<String, CompletableFuture<Mensaje>> pendientes =
            new ConcurrentHashMap<>();

    private volatile Socket socket;
    private volatile ReceptorMensajes receptor;
    private volatile Thread lector;

    public ConexionCliente(String host, int puerto) {
        this(host, puerto, false, 5001, "");
    }

    /** FASE 11.2: TLS opcional (mismo framing; solo cambia el socket). */
    public ConexionCliente(String host, int puerto, boolean tlsHabilitado, int tlsPuerto,
                           String tlsConfianza) {
        this.host = host;
        this.puerto = puerto;
        this.tlsHabilitado = tlsHabilitado;
        this.tlsPuerto = tlsPuerto;
        this.tlsConfianza = tlsConfianza == null ? "" : tlsConfianza;
    }

    @Override
    public synchronized void conectar() throws IOException {
        if (conectado()) {
            return;
        }
        socket = tlsHabilitado ? socketTls() : new Socket(host, puerto);
        socket.setTcpNoDelay(true);
        lector = new Thread(this::bucleLectura, "cliente-lector");
        lector.setDaemon(true);
        lector.start();
    }

    private Socket socketTls() throws IOException {
        try {
            javax.net.ssl.SSLSocketFactory fabrica;
            if (tlsConfianza.isBlank()) {
                fabrica = (javax.net.ssl.SSLSocketFactory)
                        javax.net.ssl.SSLSocketFactory.getDefault();
            } else {
                char[] clave = {};
                java.security.KeyStore almacen =
                        java.security.KeyStore.getInstance("JKS");
                try (java.io.InputStream in = java.nio.file.Files.newInputStream(
                        java.nio.file.Path.of(tlsConfianza))) {
                    almacen.load(in, clave);
                }
                javax.net.ssl.TrustManagerFactory confianza =
                        javax.net.ssl.TrustManagerFactory.getInstance(
                                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
                confianza.init(almacen);
                javax.net.ssl.SSLContext contexto =
                        javax.net.ssl.SSLContext.getInstance("TLS");
                contexto.init(null, confianza.getTrustManagers(), null);
                fabrica = contexto.getSocketFactory();
            }
            return fabrica.createSocket(host, tlsPuerto);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("no se pudo abrir TLS hacia " + host + ":" + tlsPuerto
                    + ": " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void desconectar() {
        pendientes.forEach((id, futuro) ->
                futuro.completeExceptionally(new EOFException("conexion cerrada")));
        pendientes.clear();
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignorada) {
                // ya cerrado
            }
        }
        socket = null;
    }

    @Override
    public boolean conectado() {
        Socket actual = socket;
        return actual != null && !actual.isClosed() && actual.isConnected();
    }

    @Override
    public void suscribir(ReceptorMensajes receptor) {
        this.receptor = receptor;
    }

    @Override
    public Mensaje login(String codigo, String contrasena) throws IOException {
        return pedir(Mensaje.builder().tipo(TipoMensaje.LOGIN)
                .codigo(codigo).contrasena(contrasena).build());
    }

    @Override
    public Mensaje enviarTexto(String remitente, String destinatario, String contenido)
            throws IOException {
        return pedir(Mensaje.builder().tipo(TipoMensaje.MENSAJE_TEXTO)
                .remitente(remitente).destinatario(destinatario).contenido(contenido).build());
    }

    @Override
    public Mensaje enviarTexto(String id, String remitente, String destinatario, String contenido)
            throws IOException {
        return pedir(Mensaje.builder().tipo(TipoMensaje.MENSAJE_TEXTO).id(id)
                .remitente(remitente).destinatario(destinatario).contenido(contenido).build());
    }

    @Override
    public Mensaje enviarImagen(String remitente, String destinatario, String nombreArchivo,
                                String mime, String contenidoBase64) throws IOException {
        return pedir(Mensaje.builder().tipo(TipoMensaje.MENSAJE_IMAGEN)
                .remitente(remitente).destinatario(destinatario).nombreArchivo(nombreArchivo)
                .mime(mime).contenidoImagen(contenidoBase64).build());
    }

    @Override
    public Mensaje enviarImagen(String id, String remitente, String destinatario,
                                String nombreArchivo, String mime, String contenidoBase64)
            throws IOException {
        return pedir(Mensaje.builder().tipo(TipoMensaje.MENSAJE_IMAGEN).id(id)
                .remitente(remitente).destinatario(destinatario).nombreArchivo(nombreArchivo)
                .mime(mime).contenidoImagen(contenidoBase64).build());
    }

    @Override
    public Mensaje difundir(String remitente, String contenido) throws IOException {
        return pedir(Mensaje.builder().tipo(TipoMensaje.BROADCAST)
                .remitente(remitente).contenido(contenido).build());
    }

    @Override
    public Mensaje difundir(String id, String remitente, String contenido) throws IOException {
        return pedir(Mensaje.builder().tipo(TipoMensaje.BROADCAST).id(id)
                .remitente(remitente).contenido(contenido).build());
    }

    @Override
    public Mensaje listarConectados() throws IOException {
        return pedir(Mensaje.builder().tipo(TipoMensaje.LISTAR_CONECTADOS).build());
    }

    @Override
    public Mensaje expulsar(String codigo) throws IOException {
        return pedir(Mensaje.builder().tipo(TipoMensaje.KICK).codigo(codigo).build());
    }

    // FASE 6

    @Override
    public Mensaje solicitarHistorial(String remitente, String destinatario, int pagina)
            throws IOException {
        return pedir(Mensaje.builder().tipo(TipoMensaje.HISTORIAL_REQ)
                .remitente(remitente).destinatario(destinatario).pagina(pagina).build());
    }

    @Override
    public Mensaje descargarArchivo(String archivoId) throws IOException {
        return pedir(Mensaje.builder().tipo(TipoMensaje.DESCARGAR_ARCHIVO)
                .archivoId(archivoId).build());
    }

    @Override
    public Mensaje enviarLectura(String lector, String autor, String idOriginal) throws IOException {
        Socket actual = socket;
        if (actual == null || actual.isClosed()) {
            throw new EOFException("sin conexion con el servidor");
        }
        Mensaje aviso = Mensaje.builder().tipo(TipoMensaje.MENSAJE_LEIDO)
                .id(UUID.randomUUID().toString())
                .fechaHora(LocalDateTime.now().toString())
                .remitente(lector).destinatario(autor).contenido(idOriginal).build();
        synchronized (candadoSalida) {
            TramaCodec.escribir(actual.getOutputStream(), aviso);
        }
        return Mensaje.builder().tipo(TipoMensaje.ACK).exito(true).build();
    }

    @Override
    public Mensaje listarUsuarios() throws IOException {
        return pedir(Mensaje.builder().tipo(TipoMensaje.LISTAR_USUARIOS).build());
    }

    @Override
    public void logout() throws IOException {
        Socket actual = socket;
        if (actual == null || actual.isClosed()) {
            return;
        }
        Mensaje aviso = Mensaje.builder().tipo(TipoMensaje.LOGOUT)
                .id(UUID.randomUUID().toString())
                .fechaHora(LocalDateTime.now().toString()).build();
        synchronized (candadoSalida) {
            TramaCodec.escribir(actual.getOutputStream(), aviso);
        }
    }

    /** Envía correlacionando por id y bloquea hasta la respuesta o el timeout. */
    Mensaje pedir(Mensaje solicitud) throws IOException {
        Socket actual = socket;
        if (actual == null || actual.isClosed()) {
            throw new EOFException("sin conexion con el servidor");
        }
        String id = solicitud.id() != null ? solicitud.id() : UUID.randomUUID().toString();
        Mensaje conId = clonarConId(solicitud, id);
        CompletableFuture<Mensaje> futuro = new CompletableFuture<>();
        pendientes.put(id, futuro);
        try {
            synchronized (candadoSalida) {
                TramaCodec.escribir(actual.getOutputStream(), conId);
            }
            return futuro.get(TIMEOUT_RESPUESTA_SEG, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pendientes.remove(id);
            throw new IOException("sin respuesta del servidor en " + TIMEOUT_RESPUESTA_SEG + " s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pendientes.remove(id);
            throw new IOException("espera interrumpida", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException("conexion cerrada esperando respuesta", e.getCause());
        }
    }

    private void bucleLectura() {
        Socket actual = socket;
        try {
            while (actual != null && !actual.isClosed()) {
                Mensaje trama = TramaCodec.leer(actual.getInputStream());
                encaminar(trama);
            }
        } catch (IOException fin) {
            // corte de red o cierre: fallar pendientes y avisar una vez
        } finally {
            pendientes.forEach((id, futuro) ->
                    futuro.completeExceptionally(new EOFException("conexion cerrada")));
            pendientes.clear();
            ReceptorMensajes obs = receptor;
            if (obs != null) {
                obs.alDesconexion();
            }
        }
    }

    private void encaminar(Mensaje trama) {
        if (trama.tipo() == TipoMensaje.CLOSE_NOTICE) {
            ReceptorMensajes obs = receptor;
            if (obs != null) {
                obs.alCierre(trama);
            }
            return;
        }
        if (trama.id() != null) {
            CompletableFuture<Mensaje> futuro = pendientes.remove(trama.id());
            if (futuro != null) {
                futuro.complete(trama);
                return;
            }
        }
        ReceptorMensajes obs = receptor;
        if (obs == null) {
            return;
        }
        switch (trama.tipo()) {
            case MENSAJE_TEXTO, MENSAJE_IMAGEN, BROADCAST, SYNC_LOGIN,
                 IMAGE_FILTERED_RESULT, HISTORIAL_PAGE,
                 DESCARGAR_ARCHIVO_RESPUESTA, MENSAJE_ENTREGADO,
                 MENSAJE_LEIDO, PRESENCIA,
                 LISTAR_USUARIOS_RESPUESTA, LISTAR_CONECTADOS_RESPUESTA -> obs.alRecibir(trama);
            case ERROR -> obs.alError(trama.mensajeError());
            default -> obs.alError("trama inesperada: " + trama.tipo());
        }
    }

    private static Mensaje clonarConId(Mensaje base, String id) {
        return Mensaje.builder().tipo(base.tipo()).id(id)
                .fechaHora(LocalDateTime.now().toString())
                .codigo(base.codigo()).contrasena(base.contrasena())
                .nombres(base.nombres()).apellidos(base.apellidos()).programa(base.programa())
                .remitente(base.remitente()).destinatario(base.destinatario())
                .contenido(base.contenido()).hashSha256(base.hashSha256())
                .numCaracteres(base.numCaracteres()).numPalabras(base.numPalabras())
                .etapasFiltrado(base.etapasFiltrado()).nombreArchivo(base.nombreArchivo())
                .tamanoArchivo(base.tamanoArchivo()).archivoId(base.archivoId()).mime(base.mime())
                .contenidoImagen(base.contenidoImagen()).pagina(base.pagina())
                .totalPaginas(base.totalPaginas()).exito(base.exito())
                .mensajeError(base.mensajeError()).ipRemitente(base.ipRemitente())
                .ipDestinatario(base.ipDestinatario())
                .usuariosConectados(base.usuariosConectados()).build();
    }
}
