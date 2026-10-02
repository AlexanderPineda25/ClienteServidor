using System;
using System.Collections.Generic;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace Mensajeria.Fachada
{
    public interface IFachadaCliente
    {
        event Action<Dictionary<string, JsonElement>>? OnMensajeRecibido;
        event Action<Dictionary<string, JsonElement>>? OnCierre;
        event Action<Dictionary<string, JsonElement>>? OnPresencia;
        string? UsuarioActual { get; }
        Task ConectarAsync();
        void Desconectar();
        Task<bool> LoginAsync(string codigo, string contrasena);
        Task<List<string>> ListarConectadosAsync();
        Task<List<Dictionary<string, object>>> ListarUsuariosAsync();
        Task MarcarLeidosAsync(string otroUsuario);
        Task ExpulsarOtrasSesionesAsync();
        List<Dictionary<string, object>> CargarHistorial(string otroUsuario, int offset = 0);
        int ContarConversacion(string otroUsuario);
        Task<int> TraerHistorialAsync(string otroUsuario, int pagina);
        List<Dictionary<string, object>> DirectorioLocal();
        Task<bool> EnviarTextoAsync(string destinatario, string contenido);
        Task<bool> EnviarImagenAsync(string destinatario, string imagePath);
        Task<bool> EnviarArchivoAsync(string destinatario, string rutaArchivo,
            IProgress<double>? progreso = null, CancellationToken cancelacion = default);
        Task DifundirAsync(string contenido);
        Task<string> DescargarArchivoAsync(string idMensaje, string archivoId, string rutaDestino);
        string? ContenidoImagenLocal(string idMensaje);
    }
}
