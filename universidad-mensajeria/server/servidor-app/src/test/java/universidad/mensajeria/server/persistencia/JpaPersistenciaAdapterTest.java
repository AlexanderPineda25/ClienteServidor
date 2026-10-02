package universidad.mensajeria.server.persistencia;

import org.junit.jupiter.api.Test;
import universidad.mensajeria.common.interno.UsuarioDTO;
import universidad.mensajeria.server.persistencia.entidades.Usuario;
import universidad.mensajeria.server.persistencia.repositorio.ArchivoRepository;
import universidad.mensajeria.server.persistencia.repositorio.ConfiguracionLimitesRepository;
import universidad.mensajeria.server.persistencia.repositorio.MensajeRepository;
import universidad.mensajeria.server.persistencia.repositorio.RegistroAccionRepository;
import universidad.mensajeria.server.persistencia.repositorio.UsuarioRepository;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JpaPersistenciaAdapterTest {

    @Test
    void csvActualizaDatosYDesactivaBajasSinEliminarHistorial() throws Exception {
        UsuarioRepository usuarios = mock(UsuarioRepository.class);
        Usuario vigente = usuario("A001", "Nombre anterior");
        Usuario baja = usuario("B002", "Usuario retirado");
        fechaRegistro(vigente, LocalDateTime.of(2024, 3, 15, 10, 30));
        LocalDateTime registroOriginal = fechaRegistro(vigente);

        when(usuarios.findByCodigo("A001")).thenReturn(Optional.of(vigente));
        when(usuarios.findByCodigo("C003")).thenReturn(Optional.empty());
        when(usuarios.findAll()).thenReturn(List.of(vigente, baja));
        when(usuarios.save(any(Usuario.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        adapter(usuarios).reconciliarUsuarios(List.of(
                new UsuarioDTO("A001", "Nombre actualizado", "Apellido", "Programa", "hash-nuevo", true),
                new UsuarioDTO("C003", "Nuevo", "Usuario", "Programa", "hash", true)));

        assertEquals("Nombre actualizado", vigente.getNombres());
        assertEquals("hash-nuevo", vigente.getPasswordHash());
        assertTrue(vigente.isActivo());
        assertEquals(registroOriginal, fechaRegistro(vigente));
        assertFalse(baja.isActivo());
        assertEquals("B002", baja.getCodigo());
        verify(usuarios).save(baja);
    }

    @Test
    void csvVacioSeRechazaAntesDeAlterarUsuarios() {
        UsuarioRepository usuarios = mock(UsuarioRepository.class);

        assertThrows(IllegalArgumentException.class,
                () -> adapter(usuarios).reconciliarUsuarios(List.of()));
        org.mockito.Mockito.verifyNoInteractions(usuarios);
    }

    private static JpaPersistenciaAdapter adapter(UsuarioRepository usuarios) {
        return new JpaPersistenciaAdapter(usuarios, mock(MensajeRepository.class),
                mock(ArchivoRepository.class), mock(RegistroAccionRepository.class),
                mock(ConfiguracionLimitesRepository.class));
    }

    private static Usuario usuario(String codigo, String nombres) {
        Usuario usuario = new Usuario();
        usuario.setCodigo(codigo);
        usuario.setNombres(nombres);
        usuario.setApellidos("Apellido");
        usuario.setProgramaAcademico("Programa");
        usuario.setPasswordHash("hash");
        usuario.setActivo(true);
        return usuario;
    }

    private static LocalDateTime fechaRegistro(Usuario usuario) throws Exception {
        Field campo = Usuario.class.getDeclaredField("fechaRegistro");
        campo.setAccessible(true);
        return (LocalDateTime) campo.get(usuario);
    }

    private static void fechaRegistro(Usuario usuario, LocalDateTime fecha) throws Exception {
        Field campo = Usuario.class.getDeclaredField("fechaRegistro");
        campo.setAccessible(true);
        campo.set(usuario, fecha);
    }
}
