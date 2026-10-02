package universidad.mensajeria.cliente.transversal.utilerias;

import universidad.mensajeria.cliente.transversal.records.UsuarioLocal;

/** Nombre de directorio para vistas; el código mantiene una identidad inequívoca. */
public final class FormatoUsuario {

    public static String visible(UsuarioLocal usuario, String codigo) {
        if (codigo == null || codigo.isBlank()) return "Usuario desconocido";
        if (usuario == null) return codigo;
        String nombre = ((usuario.nombres() == null ? "" : usuario.nombres().trim()) + " "
                + (usuario.apellidos() == null ? "" : usuario.apellidos().trim())).trim();
        return nombre.isBlank() ? codigo : nombre + " [" + codigo + "]";
    }

    private FormatoUsuario() { }
}
