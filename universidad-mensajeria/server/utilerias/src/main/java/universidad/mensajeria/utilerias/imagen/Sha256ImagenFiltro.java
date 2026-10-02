package universidad.mensajeria.utilerias.imagen;

import universidad.mensajeria.utilerias.ContextoTuberia;
import universidad.mensajeria.utilerias.Filtro;
import universidad.mensajeria.utilerias.FiltroException;
import universidad.mensajeria.utilerias.ResultadoFiltro;
import universidad.mensajeria.utilerias.ResultadoFiltro.Metadatos;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;

/** SHA-256 de los bytes de la imagen; la imagen pasa intacta. */
public final class Sha256ImagenFiltro implements Filtro {

    @Override
    public ResultadoFiltro ejecutar(List<File> entrada, ContextoTuberia ctx) throws FiltroException {
        if (entrada.isEmpty()) {
            throw new FiltroException(nombre(), "sin archivos de entrada");
        }
        try {
            byte[] bytes = Files.readAllBytes(entrada.get(0).toPath());
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return new ResultadoFiltro(entrada, Metadatos.de("hashSha256", hex.toString()));
        } catch (Exception e) {
            throw new FiltroException(nombre(), "no se pudo calcular SHA-256", e);
        }
    }

    @Override
    public String nombre() {
        return "SHA256";
    }
}
