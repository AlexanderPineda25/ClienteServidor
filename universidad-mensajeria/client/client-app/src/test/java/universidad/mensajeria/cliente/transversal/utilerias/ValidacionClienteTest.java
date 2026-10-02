package universidad.mensajeria.cliente.transversal.utilerias;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** Validaciones puras de entrada (mismas reglas del servidor). */
class ValidacionClienteTest {

    @Test
    void rechazaEntradasVacias() {
        assertThrows(IllegalArgumentException.class, () -> ValidacionCliente.codigo("  "));
        assertThrows(IllegalArgumentException.class, () -> ValidacionCliente.contrasena("12345"));
        assertThrows(IllegalArgumentException.class, () -> ValidacionCliente.contenido(null));
        assertThrows(IllegalArgumentException.class, () -> ValidacionCliente.destinatario(""));
        assertThrows(IllegalArgumentException.class, () -> ValidacionCliente.imagen(new byte[0]));
    }
}
