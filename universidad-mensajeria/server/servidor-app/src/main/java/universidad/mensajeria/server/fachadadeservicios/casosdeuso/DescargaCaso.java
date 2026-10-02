package universidad.mensajeria.server.fachadadeservicios.casosdeuso;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.common.tipos.TipoMensaje;
import universidad.mensajeria.protocolo.InterfazProtocoloComunicacion;
import universidad.mensajeria.common.interno.ArchivoDTO;
import universidad.mensajeria.server.almacenarinformacion.InterfazAlmacenarInformacion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

/**
 * DESCARGAR_ARCHIVO (§4.2): contenido Base64 bajo demanda (original o
 * derivado). La ruta queda dentro de uploads/ (defensa anti "..").
 */
public final class DescargaCaso {

    private static final Logger LOG = LoggerFactory.getLogger(DescargaCaso.class);

    private final InterfazAlmacenarInformacion almacenar;
    private final InterfazProtocoloComunicacion protocolo;
    private final ResponderCaso respuestas;
    private final Path uploadDir;

    public DescargaCaso(InterfazAlmacenarInformacion almacenar,
                        InterfazProtocoloComunicacion protocolo,
                        ResponderCaso respuestas,
                        Path uploadDir) {
        this.almacenar = almacenar;
        this.protocolo = protocolo;
        this.respuestas = respuestas;
        this.uploadDir = uploadDir.toAbsolutePath().normalize();
    }

    public void descargar(IdSesion sesion, String archivoIdStr, String idSolicitud) {
        long archivoId;
        try {
            archivoId = Long.parseLong(archivoIdStr);
        } catch (NumberFormatException e) {
            respuestas.fallo(sesion, idSolicitud, TipoMensaje.ERROR,
                    "archivoId invalido: " + archivoIdStr);
            return;
        }
        Optional<ArchivoDTO> encontrado = almacenar.buscarArchivoPorId(archivoId);
        if (encontrado.isEmpty()) {
            respuestas.fallo(sesion, idSolicitud, TipoMensaje.ERROR,
                    "archivo no encontrado: " + archivoId);
            return;
        }
        ArchivoDTO archivo = encontrado.get();
        Path rutaArchivo = uploadDir.resolve(archivo.ruta()).normalize();
        if (!rutaArchivo.startsWith(uploadDir)) {
            respuestas.fallo(sesion, idSolicitud, TipoMensaje.ERROR, "ruta de archivo invalida");
            return;
        }
        if (!Files.exists(rutaArchivo) || !Files.isReadable(rutaArchivo)) {
            respuestas.fallo(sesion, idSolicitud, TipoMensaje.ERROR,
                    "archivo no disponible en disco: " + archivo.nombre());
            return;
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(rutaArchivo);
        } catch (IOException e) {
            LOG.error("Error leyendo archivo {} (ruta {}): {}", archivoId, rutaArchivo, e.getMessage());
            respuestas.fallo(sesion, idSolicitud, TipoMensaje.ERROR,
                    "error leyendo archivo del disco");
            return;
        }
        if (bytes.length > FragmentoArchivoCaso.PARTE_BYTES) {
            descargarFragmentado(sesion, archivo, bytes, idSolicitud);
            return;
        }
        respuestas.responder(sesion, Mensaje.builder()
                .tipo(TipoMensaje.DESCARGAR_ARCHIVO_RESPUESTA)
                .id(idSolicitud)
                .fechaHora(LocalDateTime.now().toString())
                .exito(true)
                .nombreArchivo(archivo.nombre())
                .mime(archivo.mime())
                .contenidoImagen(Base64.getEncoder().encodeToString(bytes))
                .archivoId(archivoIdStr)
                .tamanoArchivo((long) bytes.length)
                .build());
        LOG.info("Descarga archivo {} ({} bytes) para {}",
                archivo.nombre(), bytes.length, sesion.codigo());
    }

    /**
     * Archivos grandes (>1 MiB): secuencia INICIO + PARTES + FIN correlacionada
     * por id=idSolicitud; cada parte cabe en la trama 16 MiB (PROTOCOLO.md §5).
     */
    private void descargarFragmentado(IdSesion sesion, ArchivoDTO archivo, byte[] bytes,
                                      String idSolicitud) {
        int totalPartes = (bytes.length + FragmentoArchivoCaso.PARTE_BYTES - 1)
                / FragmentoArchivoCaso.PARTE_BYTES;
        String ahora = LocalDateTime.now().toString();
        enviarDirecto(sesion, Mensaje.builder()
                .tipo(TipoMensaje.ARCHIVO_INICIO)
                .id(idSolicitud)
                .fechaHora(ahora)
                .nombreArchivo(archivo.nombre())
                .mime(archivo.mime())
                .tamanoArchivo((long) bytes.length)
                .totalPartes(totalPartes)
                .archivoId(idSolicitud)
                .build());
        for (int i = 0; i < totalPartes; i++) {
            int desde = i * FragmentoArchivoCaso.PARTE_BYTES;
            int hasta = Math.min(desde + FragmentoArchivoCaso.PARTE_BYTES, bytes.length);
            byte[] bloque = java.util.Arrays.copyOfRange(bytes, desde, hasta);
            enviarDirecto(sesion, Mensaje.builder()
                    .tipo(TipoMensaje.ARCHIVO_PARTE)
                    .id(idSolicitud)
                    .fechaHora(LocalDateTime.now().toString())
                    .archivoId(idSolicitud)
                    .indiceParte(i)
                    .contenidoImagen(Base64.getEncoder().encodeToString(bloque))
                    .build());
        }
        enviarDirecto(sesion, Mensaje.builder()
                .tipo(TipoMensaje.ARCHIVO_FIN)
                .id(idSolicitud)
                .fechaHora(LocalDateTime.now().toString())
                .archivoId(idSolicitud)
                .hashSha256(sha256(bytes))
                .build());
        LOG.info("Descarga fragmentada archivo {} ({} bytes, {} partes) para {}",
                archivo.nombre(), bytes.length, totalPartes, sesion.codigo());
    }

    private void enviarDirecto(IdSesion sesion, Mensaje trama) {
        try {
            protocolo.enviar(sesion, trama);
        } catch (Exception e) {
            LOG.warn("No se pudo enviar fragmento a {}: {}", sesion.codigo(), e.getMessage());
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
