package universidad.mensajeria.clienteservidor.impl;

import universidad.mensajeria.clienteservidor.InterfazClienteServidor;
import universidad.mensajeria.clienteservidor.ProveedorClienteServidor;
import universidad.mensajeria.clienteservidor.SalidaClienteServidor;
import universidad.mensajeria.clienteservidor.commands.ArchivoCommand;
import universidad.mensajeria.clienteservidor.commands.ArchivoFinCommand;
import universidad.mensajeria.clienteservidor.commands.ArchivoInicioCommand;
import universidad.mensajeria.clienteservidor.commands.ArchivoParteCommand;
import universidad.mensajeria.clienteservidor.commands.BroadcastCommand;
import universidad.mensajeria.clienteservidor.commands.CierreCommand;
import universidad.mensajeria.clienteservidor.commands.DescargaCommand;
import universidad.mensajeria.clienteservidor.commands.HistorialCommand;
import universidad.mensajeria.clienteservidor.commands.ImagenCommand;
import universidad.mensajeria.clienteservidor.commands.LeidoCommand;
import universidad.mensajeria.clienteservidor.commands.ListarCommand;
import universidad.mensajeria.clienteservidor.commands.ListarUsuariosCommand;
import universidad.mensajeria.clienteservidor.commands.LoginCommand;
import universidad.mensajeria.clienteservidor.commands.LogoutCommand;
import universidad.mensajeria.clienteservidor.commands.TextoCommand;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.common.tipos.TipoMensaje;

import java.util.List;

/**
 * Ensamblaje interno del despachador (los 12 commands del catalogo).
 * La salida real la inyecta la Fachada via registrarSalida (ServiceLoader
 * crea sin salida; Fabrica la conecta al cablear).
 */
public final class FabricaDelDespachador {

    private FabricaDelDespachador() {
    }

    public static Despachador crear(SalidaClienteServidor.FachadaSalida salida) {
        SalidaClienteServidor.SalidaAutenticacion auth = salida;
        SalidaClienteServidor.SalidaMensajeria mensajeria = salida;
        SalidaClienteServidor.SalidaConsultas consultas = salida;
        SalidaClienteServidor.SalidaRespuestas respuestas = salida;
        Despachador despachador = new Despachador(List.of(
                new LoginCommand(auth, respuestas),
                new LogoutCommand(auth, respuestas),
                new ListarCommand(consultas, respuestas),
                new ListarUsuariosCommand(consultas, respuestas),
                new TextoCommand(mensajeria, respuestas),
                new ImagenCommand(mensajeria, respuestas),
                new ArchivoCommand(mensajeria, respuestas),
                new ArchivoInicioCommand(mensajeria, respuestas),
                new ArchivoParteCommand(mensajeria, respuestas),
                new ArchivoFinCommand(mensajeria, respuestas),
                new BroadcastCommand(mensajeria, respuestas),
                new HistorialCommand(mensajeria, respuestas),
                new DescargaCommand(mensajeria, respuestas),
                new CierreCommand(auth, respuestas),
                new LeidoCommand(mensajeria, respuestas)));
        despachador.registrarSalida(salida);
        return despachador;
    }

    /** Salida nula para el SPI (Fabrica la sustituye al cablear). */
    static final SalidaClienteServidor.FachadaSalida NULA =
            new SalidaClienteServidor.FachadaSalida() {
                @Override
                public void autenticar(IdSesion sesion, String codigo, String contrasena,
                                       String idSolicitud) {
                }

                @Override
                public void cerrar(IdSesion sesion, String idSolicitud) {
                }

                @Override
                public void expulsarOtrasSesiones(IdSesion sesion, String codigo,
                                                  String idSolicitud) {
                }

                @Override
                public void recibirTexto(IdSesion sesion, String destinatario, String contenido,
                                         String idSolicitud, String ip) {
                }

                @Override
                public void recibirImagen(IdSesion sesion, String destinatario,
                                          String nombreArchivo, String mime,
                                          String contenidoBase64, String idSolicitud, String ip) {
                }

                @Override
                public void recibirArchivo(IdSesion sesion, String destinatario,
                                           String nombreArchivo, String mime,
                                           String contenidoBase64, String idSolicitud, String ip) {
                }

                @Override
                public void iniciarArchivo(IdSesion sesion, String destinatario, String nombreArchivo,
                                           String mime, long tamanoTotal, int totalPartes,
                                           String idSolicitud, String ip) {
                }

                @Override
                public void parteArchivo(IdSesion sesion, String idTransferencia, int indiceParte,
                                          String contenidoBase64, String idSolicitud, String ip) {
                }

                @Override
                public void finalizarArchivo(IdSesion sesion, String idTransferencia, String hashSha256,
                                              String idSolicitud, String ip) {
                }

                @Override
                public void difundir(IdSesion sesion, String contenido, String idSolicitud,
                                     String ip) {
                }

                @Override
                public void historial(IdSesion sesion, String otro, int pagina,
                                      String idSolicitud) {
                }

                @Override
                public void descargar(IdSesion sesion, String archivoId, String idSolicitud) {
                }

                @Override
                public void listarConectados(IdSesion sesion, String idSolicitud) {
                }

                @Override
                public void listarUsuarios(IdSesion sesion, String idSolicitud) {
                }

                @Override
                public void responder(IdSesion sesion, Mensaje respuesta) {
                }

                @Override
                public void error(IdSesion sesion, String idSolicitud, TipoMensaje tipo,
                                  String motivo) {
                }
            };

    /** Proveedor SPI: sin salida (Fabrica la conecta despues) y cableado. */
    public static final class Proveedor implements ProveedorClienteServidor {

        @Override
        public InterfazClienteServidor crear() {
            return FabricaDelDespachador.crear(NULA);
        }

        @Override
        public InterfazClienteServidor crear(SalidaClienteServidor.FachadaSalida salida) {
            return FabricaDelDespachador.crear(salida);
        }
    }
}
