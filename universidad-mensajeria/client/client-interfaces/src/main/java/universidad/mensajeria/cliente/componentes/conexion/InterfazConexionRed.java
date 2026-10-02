package universidad.mensajeria.cliente.componentes.conexion;

import universidad.mensajeria.common.tipos.Mensaje;

import java.io.IOException;

/**
 * Conexión TCP del cliente (InterfazConexionRed → socket + TramaCodec JSON,
 * §7.1). Operaciones síncronas correlacionadas por id; las entregas
 * asíncronas van al {@code ReceptorMensajes}.
 */
public interface InterfazConexionRed {

    void conectar() throws IOException;

    void desconectar();

    boolean conectado();

    void suscribir(ReceptorMensajes receptor);

    Mensaje login(String codigo, String contrasena) throws IOException;

    Mensaje enviarTexto(String remitente, String destinatario, String contenido) throws IOException;

    default Mensaje enviarTexto(String id, String remitente, String destinatario,
                                String contenido) throws IOException {
        return enviarTexto(remitente, destinatario, contenido);
    }

    Mensaje enviarImagen(String remitente, String destinatario, String nombreArchivo,
                         String mime, String contenidoBase64) throws IOException;

    default Mensaje enviarImagen(String id, String remitente, String destinatario,
                                 String nombreArchivo, String mime, String contenidoBase64)
            throws IOException {
        return enviarImagen(remitente, destinatario, nombreArchivo, mime, contenidoBase64);
    }

    Mensaje difundir(String remitente, String contenido) throws IOException;

    default Mensaje difundir(String id, String remitente, String contenido) throws IOException {
        return difundir(remitente, contenido);
    }

    Mensaje listarConectados() throws IOException;

    Mensaje expulsar(String codigo) throws IOException;

    // FASE 6 — historial paginado y descarga bajo demanda

    Mensaje solicitarHistorial(String remitente, String destinatario, int pagina) throws IOException;

    Mensaje descargarArchivo(String archivoId) throws IOException;

    /** LOGOUT sin respuesta esperada (el servidor avisa CLOSE_NOTICE). */
    void logout() throws IOException;

    /** Acuse de lectura: el lector avisa al autor que abrió el chat. */
    default Mensaje enviarLectura(String lector, String autor, String idOriginal) throws IOException {
        throw new IOException("lectura no soportada");
    }

    /** Directorio con presencia (LISTAR_USUARIOS_RESPUESTA trae JSON). */
    default Mensaje listarUsuarios() throws IOException {
        throw new IOException("listar usuarios no soportado");
    }
}
