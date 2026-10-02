using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Net.Sockets;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace Mensajeria.Componentes
{
    /// <summary>
    /// Red TCP del cliente .NET (espejo de ConexionCliente Java, Fase 5).
    /// Demultiplexor por id + escritura sincronizada; CLOSE_NOTICE SIEMPRE va
    /// a OnCierre (nunca completa un pendiente: carrera del KICK).
    /// </summary>
    public class ConexionRed : IConexionRed
    {
        private TcpClient? _cliente;
        private System.IO.Stream? _stream;
        /// <summary>FASE 11.2: TLS opcional (mismo framing sobre SslStream).</summary>
        public bool TlsHabilitado { get; set; }
        public int TlsPuerto { get; set; } = 5001;
        /// <summary>Devuelve true para aceptar el certificado (por defecto: cadena valida).</summary>
        public Func<string, bool>? ValidarCertificado { get; set; }
        private readonly ConcurrentDictionary<string, TaskCompletionSource<Dictionary<string, JsonElement>>> _pendientes = new();
        private readonly SemaphoreSlim _envioLock = new(1, 1);
        private CancellationTokenSource? _cts;

        public event Action<Dictionary<string, JsonElement>>? OnMensajeRecibido;
        public event Action<Dictionary<string, JsonElement>>? OnCierre;
        public event Action? OnDesconectado;

        public async Task ConectarAsync(string host, int puerto)
        {
            _cliente = new TcpClient();
            if (!TlsHabilitado)
            {
                await _cliente.ConnectAsync(host, puerto);
                _stream = _cliente.GetStream();
            }
            else
            {
                int tlsPuerto = TlsPuerto > 0 ? TlsPuerto : puerto;
                await _cliente.ConnectAsync(host, tlsPuerto);
                var ssl = new System.Net.Security.SslStream(
                    _cliente.GetStream(), false,
                    (remitente, certificado, cadena, errores) =>
                        ValidarCertificado?.Invoke(host)
                        ?? errores == System.Net.Security.SslPolicyErrors.None);
                await ssl.AuthenticateAsClientAsync(host);
                _stream = ssl;
            }
            _cts = new CancellationTokenSource();

            _ = Task.Run(() => EscucharAsync(_cts.Token));
        }

        public void Desconectar()
        {
            _cts?.Cancel();
            _stream?.Close();
            _cliente?.Close();
            foreach (var kv in _pendientes)
                kv.Value.TrySetCanceled();
            _pendientes.Clear();
        }

        public void Enviar(Dictionary<string, object?> msg)
        {
            if (_stream == null) throw new InvalidOperationException("No conectado");
            if (!msg.ContainsKey("id")) msg["id"] = Guid.NewGuid().ToString();
            _envioLock.Wait();
            try { TramaCodec.Escribir(_stream, msg); }
            finally { _envioLock.Release(); }
        }

        public Task<Dictionary<string, JsonElement>> Pedir(Dictionary<string, object?> msg) =>
            Pedir(msg, TimeSpan.FromSeconds(15));

        public async Task<Dictionary<string, JsonElement>> Pedir(Dictionary<string, object?> msg,
            TimeSpan timeout)
        {
            if (_stream == null) throw new InvalidOperationException("No conectado");

            string id = msg.ContainsKey("id") ? msg["id"]?.ToString()! : Guid.NewGuid().ToString();
            msg["id"] = id;

            var tcs = new TaskCompletionSource<Dictionary<string, JsonElement>>();
            _pendientes[id] = tcs;

            _envioLock.Wait();
            try { TramaCodec.Escribir(_stream, msg); }
            catch { _pendientes.TryRemove(id, out _); throw; }
            finally { _envioLock.Release(); }

            using var cts = new CancellationTokenSource(timeout);
            cts.Token.Register(() =>
            {
                if (_pendientes.TryRemove(id, out var pendingTcs))
                {
                    pendingTcs.TrySetException(new TimeoutException("sin respuesta del servidor"));
                }
            });

            return await tcs.Task;
        }

        private async Task EscucharAsync(CancellationToken token)
        {
            try
            {
                while (!token.IsCancellationRequested && _stream != null)
                {
                    var msg = await Task.Run(() => TramaCodec.Leer(_stream), token);

                    // CLOSE_NOTICE nunca completa un pendiente (carrera del KICK:
                    // el aviso viaja con el id de la solicitud y el ACK llega despues).
                    if (msg.TryGetValue("tipo", out var tipo)
                        && tipo.ValueKind == JsonValueKind.String
                        && tipo.GetString() == Transversal.TipoMensaje.CLOSE_NOTICE)
                    {
                        OnCierre?.Invoke(msg);
                        continue;
                    }

                    if (msg.TryGetValue("id", out var idElem) && idElem.ValueKind == JsonValueKind.String)
                    {
                        string id = idElem.GetString()!;
                        if (_pendientes.TryRemove(id, out var tcs))
                        {
                            tcs.TrySetResult(msg);
                            continue;
                        }
                    }

                    OnMensajeRecibido?.Invoke(msg);
                }
            }
            catch (Exception)
            {
                OnDesconectado?.Invoke();
            }
        }
    }
}
