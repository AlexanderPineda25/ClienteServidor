package universidad.mensajeria.server.fachadadeservicios.casosdeuso;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.interno.LimitesDTO;
import universidad.mensajeria.common.interno.TipoAccion;
import universidad.mensajeria.common.tipos.TipoMensaje;
import universidad.mensajeria.servicios.InterfazServiciosDisponibles;
import universidad.mensajeria.server.logdeeventos.InterfazLogDeEventos;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.BitSet;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reensamblado de archivos fragmentados 50 MB (ARCHIVO_INICIO/PARTE/FIN,
 * PROTOCOLO.md §3/§5): buffer por transferencia en trabajo/fragmentos/,
 * validacion de tamano (techo archivo.max-mb y configuracion_limites),
 * sha256 en FIN y entrega al pipeline existente via EncolarCaso.archivo.
 * Las partes viajan secuenciales (una en vuelo) correlacionadas por id.
 */
public final class FragmentoArchivoCaso {

    /** Bloque crudo por parte: el base64 resultante (~1.37 MB) cabe en la trama 16 MiB. */
    public static final int PARTE_BYTES = 1024 * 1024;

    private static final Logger LOG = LoggerFactory.getLogger(FragmentoArchivoCaso.class);

    private final InterfazServiciosDisponibles servicios;
    private final LimitesDTO limites;
    private final Path fragmentosDir;
    private final EncolarCaso encolar;
    private final ResponderCaso responder;
    private final InterfazLogDeEventos eventos;
    private final Map<String, Transferencia> transferencias = new ConcurrentHashMap<>();

    public FragmentoArchivoCaso(InterfazServiciosDisponibles servicios, LimitesDTO limites,
                                Path trabajoBase, EncolarCaso encolar,
                                ResponderCaso responder, InterfazLogDeEventos eventos) {
        this.servicios = servicios;
        this.limites = limites;
        this.fragmentosDir = trabajoBase.resolve("fragmentos");
        this.encolar = encolar;
        this.responder = responder;
        this.eventos = eventos;
    }

    private long maximoEfectivo() {
        long maximo = limites.maxTamanoArchivo();
        return maximo <= 0 ? 52_428_800L : maximo;
    }

    public void iniciar(IdSesion sesion, String destinatario, String nombreArchivo, String mime,
                        long tamanoTotal, int totalPartes, String idTransferencia, String ip) {
        purgarViejas();
        if (tamanoTotal <= 0 || totalPartes <= 0) {
            responder.fallo(sesion, idTransferencia, TipoMensaje.ERROR,
                    "tamano y totalPartes deben ser positivos");
            return;
        }
        long esperado = (tamanoTotal + PARTE_BYTES - 1) / PARTE_BYTES;
        if (totalPartes != esperado) {
            responder.fallo(sesion, idTransferencia, TipoMensaje.ERROR,
                    "totalPartes debe ser " + esperado + " para " + tamanoTotal + " bytes");
            return;
        }
        try {
            servicios.validarTamanoArchivo(tamanoTotal, maximoEfectivo());
        } catch (IllegalArgumentException e) {
            responder.fallo(sesion, idTransferencia, TipoMensaje.ERROR, e.getMessage());
            return;
        }
        try {
            Files.createDirectories(fragmentosDir);
            Path destino = ruta(idTransferencia);
            Transferencia nueva = new Transferencia(sesion.codigo(), destinatario, nombreArchivo,
                    mime, tamanoTotal, totalPartes, destino, Instant.now());
            // Mismo id reinicia el buffer: el reintento offline reenvia desde la parte 0.
            Transferencia anterior = transferencias.put(idTransferencia, nueva);
            if (anterior != null) {
                borrar(anterior);
            }
            Files.deleteIfExists(destino);
            Files.createFile(destino);
        } catch (Exception e) {
            transferencias.remove(idTransferencia);
            responder.fallo(sesion, idTransferencia, TipoMensaje.ERROR,
                    "no se pudo iniciar la transferencia: " + e.getMessage());
            return;
        }
        responder.exito(sesion, idTransferencia, TipoMensaje.ACK);
    }

    public void parte(IdSesion sesion, String idTransferencia, int indice, String base64,
                      String idSolicitud, String ip) {
        Transferencia t = transferencias.get(idTransferencia);
        if (t == null) {
            responder.fallo(sesion, idSolicitud, TipoMensaje.ERROR,
                    "transferencia desconocida: " + idTransferencia);
            return;
        }
        synchronized (t) {
            if (indice < 0 || indice >= t.totalPartes || t.recibidas.get(indice)) {
                responder.fallo(sesion, idSolicitud, TipoMensaje.ERROR,
                        "indice de parte invalido o duplicado: " + indice);
                return;
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(base64);
            } catch (IllegalArgumentException e) {
                responder.fallo(sesion, idSolicitud, TipoMensaje.ERROR, "base64 invalido");
                return;
            }
            if (t.recibidos + bytes.length > t.tamanoTotal) {
                responder.fallo(sesion, idSolicitud, TipoMensaje.ERROR,
                        "la transferencia excede el tamano anunciado");
                return;
            }
            try (OutputStream salida = Files.newOutputStream(t.archivo,
                    StandardOpenOption.APPEND)) {
                salida.write(bytes);
            } catch (Exception e) {
                responder.fallo(sesion, idSolicitud, TipoMensaje.ERROR,
                        "no se pudo guardar la parte: " + e.getMessage());
                return;
            }
            t.recibidos += bytes.length;
            t.recibidas.set(indice);
        }
        responder.exito(sesion, idSolicitud, TipoMensaje.ACK);
    }

    public void finalizar(IdSesion sesion, String idTransferencia, String hashEsperado,
                          String idSolicitud, String ip) {
        Transferencia t = transferencias.remove(idTransferencia);
        if (t == null) {
            responder.fallo(sesion, idSolicitud, TipoMensaje.ERROR,
                    "transferencia desconocida: " + idTransferencia);
            return;
        }
        synchronized (t) {
            if (t.recibidas.cardinality() != t.totalPartes || t.recibidos != t.tamanoTotal) {
                borrar(t);
                responder.fallo(sesion, idSolicitud, TipoMensaje.ERROR,
                        "transferencia incompleta: " + t.recibidas.cardinality()
                                + "/" + t.totalPartes + " partes");
                return;
            }
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(t.archivo);
                String hash = sha256(bytes);
                if (!hash.equalsIgnoreCase(hashEsperado)) {
                    responder.fallo(sesion, idSolicitud, TipoMensaje.ERROR,
                            "sha256 no coincide con el anunciado");
                    return;
                }
                encolar.archivo(sesion, t.destinatario, t.nombreArchivo, t.mime,
                        Base64.getEncoder().encodeToString(bytes), idTransferencia, ip);
            } catch (IllegalArgumentException e) {
                responder.fallo(sesion, idSolicitud, TipoMensaje.ERROR, e.getMessage());
                return;
            } catch (Exception e) {
                LOG.error("No se pudo finalizar {}: {}", idTransferencia, e.getMessage());
                responder.fallo(sesion, idSolicitud, TipoMensaje.ERROR,
                        "no se pudo finalizar la transferencia");
                return;
            } finally {
                borrar(t);
            }
            eventos.registrar(TipoAccion.MENSAJE_ARCHIVO,
                    "Archivo fragmentado %s (%d bytes, %d partes) de %s a %s".formatted(
                            t.nombreArchivo, t.tamanoTotal, t.totalPartes,
                            sesion.codigo(), t.destinatario),
                    sesion.codigo(), ip, null);
        }
    }

    int transferenciasActivas() {
        return transferencias.size();
    }

    private Path ruta(String idTransferencia) {
        String seguro = idTransferencia.replaceAll("[^A-Za-z0-9_-]", "_");
        return fragmentosDir.resolve(seguro + ".part").normalize();
    }

    private void borrar(Transferencia t) {
        try {
            Files.deleteIfExists(t.archivo);
        } catch (Exception e) {
            LOG.warn("No se pudo borrar {}: {}", t.archivo, e.getMessage());
        }
    }

    private void purgarViejas() {
        Instant limite = Instant.now().minusSeconds(30 * 60);
        transferencias.entrySet().removeIf(e -> {
            if (e.getValue().inicio.isBefore(limite)) {
                borrar(e.getValue());
                return true;
            }
            return false;
        });
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private static final class Transferencia {
        final String remitente;
        final String destinatario;
        final String nombreArchivo;
        final String mime;
        final long tamanoTotal;
        final int totalPartes;
        final Path archivo;
        final Instant inicio;
        final BitSet recibidas = new BitSet();
        long recibidos;

        Transferencia(String remitente, String destinatario, String nombreArchivo, String mime,
                      long tamanoTotal, int totalPartes, Path archivo, Instant inicio) {
            this.remitente = remitente;
            this.destinatario = destinatario;
            this.nombreArchivo = nombreArchivo;
            this.mime = mime;
            this.tamanoTotal = tamanoTotal;
            this.totalPartes = totalPartes;
            this.archivo = archivo;
            this.inicio = inicio;
        }
    }
}
