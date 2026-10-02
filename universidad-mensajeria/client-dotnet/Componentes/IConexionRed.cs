using System;
using System.Collections.Generic;
using System.Text.Json;
using System.Threading.Tasks;

namespace Mensajeria.Componentes
{
    /// <summary>Contrato de red (espejo de InterfazConexionRed Java).</summary>
    public interface IConexionRed
    {
        event Action<Dictionary<string, JsonElement>>? OnMensajeRecibido;
        event Action<Dictionary<string, JsonElement>>? OnCierre;
        event Action? OnDesconectado;
        Task ConectarAsync(string host, int puerto);
        void Desconectar();
        void Enviar(Dictionary<string, object?> msg);
        Task<Dictionary<string, JsonElement>> Pedir(Dictionary<string, object?> msg);
        Task<Dictionary<string, JsonElement>> Pedir(Dictionary<string, object?> msg, TimeSpan timeout);
    }
}
