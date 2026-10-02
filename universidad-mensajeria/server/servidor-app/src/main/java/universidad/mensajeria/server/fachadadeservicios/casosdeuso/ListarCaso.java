package universidad.mensajeria.server.fachadadeservicios.casosdeuso;

import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.interno.UsuarioResumen;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.common.tipos.TipoMensaje;
import universidad.mensajeria.protocolo.InterfazProtocoloComunicacion;
import universidad.mensajeria.server.conexionesclientes.InterfazConexionesClientes;
import universidad.mensajeria.usuarios.InterfazUsuariosDisponibles;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Consultas de presencia (§13.15) y directorio. */
public final class ListarCaso {

    private final InterfazProtocoloComunicacion protocolo;
    private final InterfazConexionesClientes conexiones;
    private final InterfazUsuariosDisponibles usuarios;

    public ListarCaso(InterfazProtocoloComunicacion protocolo,
                      InterfazConexionesClientes conexiones,
                      InterfazUsuariosDisponibles usuarios) {
        this.protocolo = protocolo;
        this.conexiones = conexiones;
        this.usuarios = usuarios;
    }

    public void listarConectados(IdSesion sesion, String idSolicitud) {
        List<String> conectados = conexiones.codigosConectados();
        try {
            protocolo.enviar(sesion, Mensaje.builder()
                    .tipo(TipoMensaje.LISTAR_CONECTADOS_RESPUESTA)
                    .id(idSolicitud)
                    .fechaHora(LocalDateTime.now().toString())
                    .usuariosConectados(conectados)
                    .build());
        } catch (Exception ignorada) {
            // sesion muerta: el transporte la poda al detectar el corte
        }
    }

    public void listarUsuarios(IdSesion sesion, String idSolicitud) {
        Set<String> conectados = new HashSet<>(conexiones.codigosConectados());
        List<UsuarioResumen> resumen = usuarios.listar().stream()
                .map(u -> new UsuarioResumen(u.codigo(), u.nombres(), u.apellidos(),
                        u.programa(), conectados.contains(u.codigo())))
                .toList();
        try {
            protocolo.enviar(sesion, Mensaje.builder()
                    .tipo(TipoMensaje.LISTAR_USUARIOS_RESPUESTA)
                    .id(idSolicitud)
                    .fechaHora(LocalDateTime.now().toString())
                    .exito(true)
                    .contenido(new com.google.gson.Gson().toJson(resumen))
                    .build());
        } catch (Exception ignorada) {
            // sesion muerta: el transporte la poda al detectar el corte
        }
    }
}
