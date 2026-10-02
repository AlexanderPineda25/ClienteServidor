package universidad.mensajeria.server.mensajes;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import universidad.mensajeria.common.interno.ArchivoDTO;
import universidad.mensajeria.common.interno.EventoEtapa;
import universidad.mensajeria.common.interno.InformeFiltroDTO;
import universidad.mensajeria.common.interno.MensajeResumenDTO;
import universidad.mensajeria.common.interno.ResultadoMensajeDTO;
import universidad.mensajeria.common.interno.UsuarioNoEncontradoException;
import universidad.mensajeria.common.interno.UsuarioDTO;

import universidad.mensajeria.server.almacenarinformacion.InterfazAlmacenarInformacion;
import universidad.mensajeria.utilerias.ContextoTuberia;
import universidad.mensajeria.utilerias.Tuberia;
import universidad.mensajeria.utilerias.InterfazTuberiaFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Mensajes del diagrama (§2.2, PLAN2 §3): UNICO que compone tuberias y UNICO
 * con flecha a AlmacenarInformacion (junto a LogDeEventos). Opera SOLO con
 * DTOs: jamas ve persistencia.entidades (toda entidad nace dentro de
 * AlmacenarInformacion). Cada mensaje se procesa en una tarea del pool de
 * filtros (paralelismo ENTRE mensajes, §3.4); dentro, la cadena es
 * estrictamente secuencial. Sin anotaciones de framework.
 */
public class Mensajes implements InterfazMensajes {

    private static final Logger LOG = LoggerFactory.getLogger(Mensajes.class);
    private static final Gson JSON = new Gson();

    private final InterfazAlmacenarInformacion almacenar;
    private final InterfazTuberiaFactory tuberias;
    private final Executor poolFiltros;
    private final Consumer<EventoEtapa> oyente;

    public Mensajes(InterfazAlmacenarInformacion almacenar, InterfazTuberiaFactory tuberias,
                    Executor poolFiltros, Consumer<EventoEtapa> oyente) {
        this.almacenar = almacenar;
        this.tuberias = tuberias;
        this.poolFiltros = poolFiltros;
        this.oyente = oyente;
    }

    @Override
    public CompletableFuture<ResultadoMensajeDTO> procesarTexto(String codigoRemitente,
                                                                String codigoDestinatario,
                                                                String contenido,
                                                                String ipRemitente) {
        return CompletableFuture.supplyAsync(() -> {
            if (contenido == null || contenido.isBlank()) {
                throw new IllegalArgumentException("el contenido de texto no puede estar vacio");
            }
            try {
                exigirUsuarios(codigoRemitente, codigoDestinatario);
                File original = almacenar.guardarOriginalTexto(contenido, codigoRemitente);
                Path carpeta = original.toPath().getParent();
                Tuberia.ResultadoTuberia resultado = tuberias.texto()
                        .ejecutar(List.of(original), new ContextoTuberia(carpeta, oyente));
                String hash = resultado.metadatos().obtener("hashSha256");
                int caracteres = Integer.parseInt(resultado.metadatos().obtener("numCaracteres"));
                int palabras = Integer.parseInt(resultado.metadatos().obtener("numPalabras"));

                // El texto tambien queda en uploads/, en su subcarpeta por remitente.
                UsuarioDTO propietario = almacenar.buscarUsuario(codigoRemitente).orElseThrow();
                String carpetaPropietario = (propietario.nombres() + " "
                        + propietario.apellidos() + " [" + propietario.codigo() + "]").trim();
                List<String> rutas = almacenar.promocionarAUploads(hash, original,
                        "original.txt", carpetaPropietario, "textos");
                long id = almacenar.guardarMensajeTexto(codigoRemitente, codigoDestinatario,
                        contenido, hash, caracteres, palabras, ipRemitente);
                limpiar(carpeta);
                return new ResultadoMensajeDTO(id, hash, caracteres, palabras,
                        rutas, null, aEventos(resultado));
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("no se pudo procesar el texto", e);
            }
        }, poolFiltros);
    }

    @Override
    public CompletableFuture<ResultadoMensajeDTO> procesarImagen(String codigoRemitente,
                                                                 String codigoDestinatario,
                                                                 byte[] bytesImagen,
                                                                 String nombreOriginal,
                                                                 String mime,
                                                                 String ipRemitente) {
        return CompletableFuture.supplyAsync(() -> {
            if (bytesImagen == null || bytesImagen.length == 0) {
                throw new IllegalArgumentException("los bytes de la imagen no pueden estar vacios");
            }
            if (!universidad.mensajeria.utilerias.imagen.Imagenes.esImagenValida(bytesImagen)) {
                throw new IllegalArgumentException(
                        "el archivo no es una imagen PNG/JPEG/GIF/BMP valida");
            }
            try {
                long maximo = almacenar.leerLimites().maxTamanoArchivo();
                if (bytesImagen.length > maximo) {
                    throw new IllegalArgumentException(
                            "la imagen supera el maximo permitido de " + maximo + " bytes");
                }
                exigirUsuarios(codigoRemitente, codigoDestinatario);
                UsuarioDTO propietario = almacenar.buscarUsuario(codigoRemitente).orElseThrow();
                String carpetaPropietario = (propietario.nombres() + " "
                        + propietario.apellidos() + " [" + propietario.codigo() + "]").trim();
                File original = almacenar.guardarOriginalBinario(bytesImagen, codigoRemitente,
                        nombreOriginal == null || nombreOriginal.isBlank() ? "imagen.png" : nombreOriginal);
                Path carpeta = original.toPath().getParent();
                Tuberia.ResultadoTuberia resultado = tuberias.imagen()
                        .ejecutar(List.of(original), new ContextoTuberia(carpeta, oyente));
                String hash = resultado.metadatos().obtener("hashSha256");

                List<String> rutas = almacenar.promocionarAUploads(hash, original, nombreOriginal,
                        carpetaPropietario);
                limpiar(carpeta);
                String etapasJson = JSON.toJson(Map.of(
                        "hash", hash,
                        "etapas", traza(resultado),
                        "rutas", rutas));
                ArchivoDTO archivo = almacenar.registrarImagen(
                        nombreOriginal == null || nombreOriginal.isBlank() ? "imagen.png" : nombreOriginal,
                        rutas.get(0), bytesImagen.length, mime, hash, codigoRemitente);
                long id = almacenar.guardarMensajeImagen(codigoRemitente, codigoDestinatario,
                        archivo.nombre(), archivo.ruta(), archivo.tamano(), archivo.tipo(),
                        archivo.mime(), hash, etapasJson, ipRemitente);
                return new ResultadoMensajeDTO(id, hash, null, null,
                        rutas, archivo.id(), aEventos(resultado));
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("no se pudo procesar la imagen", e);
            }
        }, poolFiltros);
    }

    @Override
    public CompletableFuture<ResultadoMensajeDTO> procesarArchivo(String codigoRemitente,
                                                                  String codigoDestinatario,
                                                                  byte[] bytesArchivo,
                                                                  String nombreOriginal,
                                                                  String mime,
                                                                  String ipRemitente) {
        return CompletableFuture.supplyAsync(() -> {
            if (bytesArchivo == null || bytesArchivo.length == 0) {
                throw new IllegalArgumentException("los bytes del archivo no pueden estar vacios");
            }
            try {
                long maximo = almacenar.leerLimites().maxTamanoArchivo();
                if (bytesArchivo.length > maximo) {
                    throw new IllegalArgumentException(
                            "el archivo supera el maximo permitido de " + maximo + " bytes");
                }
                exigirUsuarios(codigoRemitente, codigoDestinatario);
                File original = almacenar.guardarOriginalBinario(bytesArchivo, codigoRemitente,
                        nombreOriginal == null || nombreOriginal.isBlank() ? "archivo.bin" : nombreOriginal);
                Path carpeta = original.toPath().getParent();
                Tuberia.ResultadoTuberia resultado = tuberias.archivo()
                        .ejecutar(List.of(original), new ContextoTuberia(carpeta, oyente));
                String hash = resultado.metadatos().obtener("hashSha256");

                // Como las imagenes: carpeta por remitente, sin subcarpeta.
                UsuarioDTO propietario = almacenar.buscarUsuario(codigoRemitente).orElseThrow();
                String carpetaPropietario = (propietario.nombres() + " "
                        + propietario.apellidos() + " [" + propietario.codigo() + "]").trim();
                List<String> rutas = almacenar.promocionarAUploads(hash, original,
                        nombreOriginal, carpetaPropietario);
                limpiar(carpeta);
                ArchivoDTO archivo = almacenar.registrarArchivo(
                        nombreOriginal == null || nombreOriginal.isBlank() ? "archivo.bin" : nombreOriginal,
                        rutas.get(0), bytesArchivo.length, "ARCHIVO", mime, hash, codigoRemitente);
                long id = almacenar.guardarMensajeArchivo(codigoRemitente, codigoDestinatario,
                        archivo.nombre(), archivo.ruta(), archivo.tamano(), archivo.tipo(),
                        archivo.mime(), hash, ipRemitente);
                return new ResultadoMensajeDTO(id, hash, null, null,
                        rutas, archivo.id(), aEventos(resultado));
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("no se pudo procesar el archivo", e);
            }
        }, poolFiltros);
    }

    private void exigirUsuarios(String codigoRemitente, String codigoDestinatario) {
        if (almacenar.buscarUsuario(codigoRemitente).isEmpty()) {
            throw new UsuarioNoEncontradoException(codigoRemitente);
        }
        if (almacenar.buscarUsuario(codigoDestinatario).isEmpty()) {
            throw new UsuarioNoEncontradoException(codigoDestinatario);
        }
    }

    private static List<EventoEtapa> aEventos(Tuberia.ResultadoTuberia resultado) {
        return List.copyOf(resultado.traza());
    }

    @Override
    public List<MensajeResumenDTO> detalle(InformeFiltroDTO filtro) {
        // FASE 9 (§8): alimenta el Informe 3; la consulta JOIN FETCH vive en
        // AlmacenarInformacion (unico con acceso a persistencia).
        return almacenar.historicoMensajes(filtro);
    }

    private static List<Map<String, Object>> traza(Tuberia.ResultadoTuberia resultado) {
        List<Map<String, Object>> filas = new ArrayList<>();
        for (EventoEtapa etapa : resultado.traza()) {
            Map<String, Object> fila = new LinkedHashMap<>();
            fila.put("etapa", etapa.etapa());
            fila.put("ms", etapa.ms());
            fila.put("ruta", etapa.rutaSalida());
            filas.add(fila);
        }
        return filas;
    }

    private static void limpiar(Path carpeta) {
        try (var flujos = Files.list(carpeta)) {
            flujos.forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception e) {
                    LOG.warn("No se pudo limpiar {}: {}", p, e.getMessage());
                }
            });
            Files.deleteIfExists(carpeta);
        } catch (Exception e) {
            LOG.warn("No se pudo limpiar {}: {}", carpeta, e.getMessage());
        }
    }
}
