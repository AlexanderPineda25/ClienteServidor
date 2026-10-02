package universidad.mensajeria.server.inicio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import universidad.mensajeria.common.interno.UsuarioDTO;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Semilla desde archivo plano (req. #1). Lee el CSV y devuelve DTOs;
 * Fabrica decide que persiste y que siembra en memoria (paso de
 * construccion, no flecha de uso, PLAN2 §2.5 Regla 6).
 */
public final class SemillaUsuarios {

    private static final Logger LOG = LoggerFactory.getLogger(SemillaUsuarios.class);

    private SemillaUsuarios() {
    }

    public static List<UsuarioDTO> leer(InputStream csv, VerificadorSimple cifrador) throws Exception {
        List<UsuarioDTO> usuarios = new ArrayList<>();
        try (BufferedReader lector = new BufferedReader(
                new InputStreamReader(csv, StandardCharsets.UTF_8))) {
            String linea = lector.readLine(); // cabecera
            while ((linea = lector.readLine()) != null) {
                if (linea.isBlank()) {
                    continue;
                }
                String[] campos = linea.split(",", -1);
                if (campos.length < 5) {
                    LOG.warn("Linea de semilla ignorada (columnas < 5): {}", linea);
                    continue;
                }
                usuarios.add(new UsuarioDTO(campos[0].trim(), campos[1].trim(), campos[2].trim(),
                        campos[3].trim(), cifrador.cifrar(campos[4].trim()), true));
            }
        }
        return usuarios;
    }

    /** Cifrado aportado por quien arranca (BCrypt Spring en prod). */
    public interface VerificadorSimple {
        String cifrar(String plano);
    }
}
