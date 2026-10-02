package universidad.mensajeria.server.fachadadeservicios.casosdeuso;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.interno.LimitesDTO;
import universidad.mensajeria.common.interno.TipoAccion;
import universidad.mensajeria.common.interno.UsuarioDTO;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.common.tipos.TipoMensaje;

import universidad.mensajeria.server.almacenarinformacion.InterfazAlmacenarInformacion;
import universidad.mensajeria.server.conexionesclientes.InterfazConexionesClientes;
import universidad.mensajeria.server.logdeeventos.InterfazLogDeEventos;

import universidad.mensajeria.servicios.InterfazServiciosDisponibles;

import universidad.mensajeria.usuarios.InterfazUsuariosDisponibles;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * LOGIN (caso de uso): limites → credenciales → registro vivo → respuesta →
 * SYNC_LOGIN. La sesion TCP la autentica el transporte (Regla 4).
 */
public final class AutenticarCaso {

    private static final Logger LOG = LoggerFactory.getLogger(AutenticarCaso.class);
    private static final int SYNC_MAX = 10;

    private final universidad.mensajeria.protocolo.InterfazProtocoloComunicacion protocolo;
    private final InterfazServiciosDisponibles servicios;
    private final InterfazUsuariosDisponibles usuarios;
    private final InterfazConexionesClientes conexiones;
    private final InterfazAlmacenarInformacion almacenar;
    private final InterfazLogDeEventos eventos;
    private final InterfazUsuariosDisponibles.VerificadorCredenciales verificador;
    private final LimitesDTO limites;
    private final ResponderCaso respuestas;

    public AutenticarCaso(universidad.mensajeria.protocolo.InterfazProtocoloComunicacion protocolo,
                          InterfazServiciosDisponibles servicios,
                          InterfazUsuariosDisponibles usuarios,
                          InterfazConexionesClientes conexiones,
                          InterfazAlmacenarInformacion almacenar,
                          InterfazLogDeEventos eventos,
                          InterfazUsuariosDisponibles.VerificadorCredenciales verificador,
                          LimitesDTO limites,
                          ResponderCaso respuestas) {
        this.protocolo = protocolo;
        this.servicios = servicios;
        this.usuarios = usuarios;
        this.conexiones = conexiones;
        this.almacenar = almacenar;
        this.eventos = eventos;
        this.verificador = verificador;
        this.limites = limites;
        this.respuestas = respuestas;
    }

    public void autenticar(IdSesion sesion, String codigo, String contrasena, String idSolicitud) {
        if (codigo != null && "SERVIDOR".equalsIgnoreCase(codigo.trim())) {
            LOG.warn("Intento de login como cuenta de sistema SERVIDOR desde {}", sesion.direccionIp());
            eventos.registrar(TipoAccion.LOGIN_FALLIDO, "Cuenta de sistema sin inicio de sesion",
                    codigo, sesion.direccionIp(), null);
            respuestas.fallo(sesion, idSolicitud, TipoMensaje.LOGIN_RESPUESTA,
                    "SERVIDOR es buzon del sistema: no admite inicio de sesion");
            return;
        }
        if (!servicios.puedeIniciarSesion(codigo, conexiones.sesionesDe(codigo).size(),
                conexiones.totalSesiones(), limites)) {
            LOG.warn("Limite de conexiones alcanzado para {}", codigo);
            respuestas.fallo(sesion, idSolicitud, TipoMensaje.LOGIN_RESPUESTA,
                    "limite de conexiones alcanzado");
            respuestas.fallo(sesion, idSolicitud, TipoMensaje.CLOSE_NOTICE,
                    "conexion rechazada por limite");
            eventos.registrar(TipoAccion.LIMITE_ALCANZADO,
                    "Limite alcanzado para " + codigo, codigo, sesion.direccionIp(), null);
            protocolo.cerrarSesion(sesion, "conexion rechazada por limite");
            return;
        }

        Optional<UsuarioDTO> usuario = usuarios.validarCredenciales(codigo, contrasena, verificador);
        if (usuario.isEmpty()) {
            LOG.warn("Login fallido para {} desde {}", codigo, sesion.direccionIp());
            eventos.registrar(TipoAccion.LOGIN_FALLIDO, "Credenciales invalidas",
                    codigo, sesion.direccionIp(), null);
            respuestas.fallo(sesion, idSolicitud, TipoMensaje.LOGIN_RESPUESTA,
                    "credenciales invalidas");
            return;
        }

        IdSesion autenticada = protocolo.autenticar(sesion, usuario.get().codigo());
        boolean eraNuevo = conexiones.sesionesDe(usuario.get().codigo()).isEmpty();
        conexiones.registrar(usuario.get().codigo(), autenticada);

        LOG.info("Login OK de {} desde {}", codigo, sesion.direccionIp());
        respuestas.responder(autenticada, Mensaje.builder()
                .tipo(TipoMensaje.LOGIN_RESPUESTA)
                .id(idSolicitud)
                .fechaHora(LocalDateTime.now().toString())
                .exito(true)
                .ipRemitente(sesion.direccionIp())
                .build());
        if (eraNuevo) {
            emitirPresenciaExcepto(usuario.get().codigo(), true, autenticada);
        }
        eventos.registrar(TipoAccion.LOGIN, "Login OK", usuario.get().codigo(),
                sesion.direccionIp(), null);
        enviarSync(usuario.get().codigo(), autenticada);
    }

    private void emitirPresenciaExcepto(String codigo, boolean conectado, IdSesion excepto) {
        try {
            java.util.List<String> todos = conexiones.codigosConectados();
            Mensaje aviso = Mensaje.builder()
                    .tipo(TipoMensaje.PRESENCIA)
                    .fechaHora(LocalDateTime.now().toString())
                    .codigo(codigo)
                    .contenido(conectado ? "conectado" : "desconectado")
                    .usuariosConectados(todos)
                    .build();
            for (String destino : todos) {
                for (IdSesion s : conexiones.sesionesDe(destino)) {
                    if (excepto != null && s.id().equals(excepto.id())) {
                        continue;
                    }
                    try {
                        protocolo.enviar(s, aviso);
                    } catch (Exception ignorada) {
                        // presencia best-effort
                    }
                }
            }
        } catch (Exception ignorada) {
            // presencia nunca tumba el login
        }
    }

    private void enviarSync(String codigo, IdSesion sesion) {
        try {
            var pendientes = almacenar.pendientesPara(codigo, SYNC_MAX);
            for (var m : pendientes) {
                Mensaje.Builder sync = Mensaje.builder()
                        .tipo(TipoMensaje.SYNC_LOGIN)
                        .fechaHora(m.fechaEnvio())
                        .remitente(m.remitente())
                        .destinatario(codigo)
                        .hashSha256(m.hashSha256())
                        .numCaracteres(m.numCaracteres())
                        .numPalabras(m.numPalabras());
                if (m.contenido() != null) {
                    sync.contenido(m.contenido());
                }
                if (m.nombreArchivo() != null) {
                    sync.nombreArchivo(m.nombreArchivo())
                            .tamanoArchivo(m.tamanoArchivo())
                            .archivoId(m.archivoId() == null ? null : String.valueOf(m.archivoId()));
                }
                respuestas.responder(sesion, sync.build());
            }
            if (!pendientes.isEmpty()) {
                LOG.info("SYNC_LOGIN: {} mensajes enviados a {}",
                        pendientes.size(), codigo);
            }
        } catch (Exception e) {
            LOG.warn("Error en SYNC_LOGIN para {}: {}", codigo, e.getMessage());
        }
    }
}
