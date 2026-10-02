package universidad.mensajeria.usuarios;

import org.junit.jupiter.api.Test;
import universidad.mensajeria.common.interno.UsuarioDTO;
import universidad.mensajeria.common.interno.UsuarioYaExisteException;
import universidad.mensajeria.usuarios.impl.Usuarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Directorio en memoria con verificador inyectado (sin Spring, sin BD). */
class UsuariosTest {

    private static final InterfazUsuariosDisponibles.VerificadorCredenciales PLANO =
            new InterfazUsuariosDisponibles.VerificadorCredenciales() {
                @Override
                public boolean verificar(String plano, String hash) {
                    return ("hash:" + plano).equals(hash);
                }

                @Override
                public String cifrar(String plano) {
                    return "hash:" + plano;
                }
            };

    @Test
    void registrarValidarYListar() {
        InterfazUsuariosDisponibles usuarios = new Usuarios();

        UsuarioDTO creado = usuarios.registrar("A001", "hash:Secreta123", "Ana", "Lopez", "Sistemas");
        assertEquals("A001", creado.codigo());
        assertTrue(usuarios.existe("A001"));
        assertTrue(usuarios.validarCredenciales("A001", "Secreta123", PLANO).isPresent());
        assertTrue(usuarios.validarCredenciales("A001", "otra", PLANO).isEmpty());
        assertTrue(usuarios.validarCredenciales("ZZZ", "x", PLANO).isEmpty());
        assertEquals(1, usuarios.listar().size());
        assertThrows(UsuarioYaExisteException.class,
                () -> usuarios.registrar("A001", "hash:otra", "Ana", "Lopez", "Sistemas"));
    }

    @Test
    void inactivoNoAutenticaYSembrarNoDuplica() {
        InterfazUsuariosDisponibles usuarios = new Usuarios();
        usuarios.sembrar(java.util.List.of(
                new UsuarioDTO("A001", "Ana", "Lopez", "Sistemas", "hash:Secreta123", false)));
        assertFalse(usuarios.validarCredenciales("A001", "Secreta123", PLANO).isPresent());

        usuarios.sembrar(java.util.List.of(
                new UsuarioDTO("A001", "Ana", "Lopez", "Sistemas", "hash:Secreta123", false),
                new UsuarioDTO("B002", "Luis", "Garcia", "Sistemas", "hash:Secreta123", true)));
        assertEquals(2, usuarios.listar().size());
    }
}
