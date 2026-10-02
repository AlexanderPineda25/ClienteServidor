package universidad.mensajeria.cliente.transversal.utilerias;

import org.junit.jupiter.api.Test;
import universidad.mensajeria.cliente.transversal.records.UsuarioLocal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FormatoUsuarioTest {

    @Test
    void muestraNombreCompletoConCodigoYUsaCodigoCuandoFaltanDatos() {
        UsuarioLocal usuario = new UsuarioLocal("A001", "Ana", "Pérez", "Sistemas",
                true, "2026-01-01", "2026-01-01");
        assertEquals("Ana Pérez [A001]", FormatoUsuario.visible(usuario, "A001"));
        assertEquals("B002", FormatoUsuario.visible(null, "B002"));
        assertEquals("B003", FormatoUsuario.visible(new UsuarioLocal("B003", "", "", "",
                false, "", ""), "B003"));
    }
}
