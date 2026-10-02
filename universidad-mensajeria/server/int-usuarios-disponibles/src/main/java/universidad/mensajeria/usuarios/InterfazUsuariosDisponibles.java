package universidad.mensajeria.usuarios;

import universidad.mensajeria.common.interno.UsuarioDTO;

import java.util.List;
import java.util.Optional;

/**
 * Directorio en memoria (PLAN2 §2.5 Regla 6): registro, validacion de
 * codigo/contrasena y listados. Sin persistencia propia: la siembra inicial
 * y el registro en caliente los orquesta la Fachada (eventos).
 */
public interface InterfazUsuariosDisponibles {

    /** Valida codigo + hash (BCrypt via callback); respeta estado/activo. */
    Optional<UsuarioDTO> validarCredenciales(String codigo, String contrasena,
                                             VerificadorCredenciales verificador);

    /** Alta en memoria. Lanza UsuarioYaExisteException si el codigo existe. */
    UsuarioDTO registrar(String codigo, String hashContrasena, String nombres,
                         String apellidos, String programa);

    List<UsuarioDTO> listar();

    Optional<UsuarioDTO> buscar(String codigo);

    boolean existe(String codigo);

    /** Siembra inicial (CSV + filas MySQL leidas por la Fachada). */
    void sembrar(List<UsuarioDTO> usuarios);

    /** Verificacion de contrasena aportada por servidor-app (BCrypt Spring). */
    interface VerificadorCredenciales {
        boolean verificar(String plano, String hash);

        String cifrar(String plano);
    }
}
