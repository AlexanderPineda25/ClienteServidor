package universidad.mensajeria.usuarios.impl;

import universidad.mensajeria.common.interno.UsuarioDTO;
import universidad.mensajeria.common.interno.UsuarioYaExisteException;
import universidad.mensajeria.usuarios.InterfazUsuariosDisponibles;
import universidad.mensajeria.usuarios.ProveedorUsuariosDisponibles;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Directorio en memoria (PLAN2 §2.5 Regla 6): sin persistencia propia.
 * La verificacion BCrypt la aporta la Fachada via callback.
 */
public final class Usuarios implements InterfazUsuariosDisponibles {

    private final ConcurrentHashMap<String, UsuarioDTO> directorio = new ConcurrentHashMap<>();

    @Override
    public Optional<UsuarioDTO> validarCredenciales(String codigo, String contrasena,
                                                    VerificadorCredenciales verificador) {
        if (codigo == null || codigo.isBlank() || contrasena == null || contrasena.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(directorio.get(codigo.trim()))
                .filter(UsuarioDTO::activo)
                .filter(u -> verificador.verificar(contrasena, u.hashContrasena()));
    }

    @Override
    public UsuarioDTO registrar(String codigo, String hashContrasena, String nombres,
                                String apellidos, String programa) {
        if (codigo == null || codigo.isBlank()) {
            throw new IllegalArgumentException("codigo es obligatorio");
        }
        UsuarioDTO nuevo = new UsuarioDTO(codigo.trim(), nombres.trim(), apellidos.trim(),
                programa.trim(), hashContrasena, true);
        if (directorio.putIfAbsent(nuevo.codigo(), nuevo) != null) {
            throw new UsuarioYaExisteException(codigo);
        }
        return nuevo;
    }

    @Override
    public List<UsuarioDTO> listar() {
        return directorio.values().stream()
                .sorted((a, b) -> a.codigo().compareTo(b.codigo()))
                .toList();
    }

    @Override
    public Optional<UsuarioDTO> buscar(String codigo) {
        return Optional.ofNullable(codigo == null ? null : directorio.get(codigo.trim()));
    }

    @Override
    public boolean existe(String codigo) {
        return codigo != null && directorio.containsKey(codigo.trim());
    }

    @Override
    public void sembrar(List<UsuarioDTO> usuarios) {
        for (UsuarioDTO u : usuarios) {
            directorio.putIfAbsent(u.codigo(), u);
        }
    }

    /** Registro SPI (PLAN2 §2.4). */
    public static final class Proveedor implements ProveedorUsuariosDisponibles {

        @Override
        public InterfazUsuariosDisponibles crear() {
            return new Usuarios();
        }
    }
}
