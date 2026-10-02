using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using Mensajeria.Componentes;
using Mensajeria.Fachada;
using Mensajeria.Store;
using Xunit;

namespace Mensajeria.Tests
{
    /// <summary>Fachada .NET con red falsa y SQLite real: online/offline/reintento.</summary>
    public class FachadaTests : IDisposable
    {
        private readonly string _dbPath;
        private readonly LocalStore _store;
        private readonly RedFalsa _red;
        private readonly FachadaCliente _f;
        private readonly List<Dictionary<string, JsonElement>> _avisos = new();

        public FachadaTests()
        {
            _dbPath = Path.Combine(Path.GetTempPath(), Guid.NewGuid() + ".db");
            _store = new LocalStore(_dbPath);
            _red = new RedFalsa();
            var hist = new HistorialLocal(_store);
            var cache = new CacheUsuarios(_store);
            _f = new FachadaCliente(_red, new Autenticacion(_red), hist, cache);
            _f.OnMensajeRecibido += m => _avisos.Add(m);
            Assert.True(_f.LoginAsync("A001", "x").GetAwaiter().GetResult());
        }

        public void Dispose()
        {
            _store.Dispose();
            try { File.Delete(_dbPath); } catch { }
        }

        [Fact]
        public async Task EnviarTextoOnlineGuardaMetadatos()
        {
            Assert.True(await _f.EnviarTextoAsync("B002", "hola"));
            var conv = _f.CargarHistorial("B002");
            Assert.Single(conv);
            Assert.Equal("hola", conv[0]["contenido"]);
            Assert.Equal("h", conv[0]["hash"]);
            Assert.Equal(conv[0]["id"], _red.Peticiones.Last()["id"]);
            Assert.Empty(_f.Pendientes());
        }

        [Fact]
        public void AcuseActualizaElMismoRegistroPorId()
        {
            _f.EnviarTextoAsync("B002", "hola").GetAwaiter().GetResult();
            string id = (string)_f.CargarHistorial("B002")[0]["id"];
            _red.Recibir(Doc(new Dictionary<string, object?>
                { { "tipo", "MENSAJE_ENTREGADO" }, { "contenido", id } }));
            Assert.Equal("ENTREGADO", _f.CargarHistorial("B002")[0]["estado"]);
            _red.Recibir(Doc(new Dictionary<string, object?>
                { { "tipo", "MENSAJE_LEIDO" }, { "contenido", id } }));
            Assert.Equal("LEIDO", _f.CargarHistorial("B002")[0]["estado"]);
        }

        [Fact]
        public async Task AcuseDeLecturaOfflineSeReintentaSinDuplicarse()
        {
            _red.Recibir(Doc(new Dictionary<string, object?>
                { { "tipo", "MENSAJE_TEXTO" }, { "id", "entrante-1" },
                  { "remitente", "B002" }, { "contenido", "hola" },
                  { "fechaHora", "2026-10-01T10:00:00" } }));
            _red.Caida = true;
            await _f.MarcarLeidosAsync("B002");
            Assert.Single(_f.Pendientes());
            _red.Caida = false;
            Assert.Equal(1, await _f.ReintentarPendientesAsync());
            Assert.Empty(_f.Pendientes());
            Assert.Contains(_red.Peticiones, p => (string?)p.GetValueOrDefault("tipo") == "MENSAJE_LEIDO"
                && (string?)p.GetValueOrDefault("contenido") == "entrante-1");
        }

        [Fact]
        public async Task ErrorServidorSeElevaNoSeTraga()
        {
            await Assert.ThrowsAsync<InvalidOperationException>(() =>
                _f.EnviarTextoAsync("ZZZ", "hola"));
        }

        [Fact]
        public async Task EnviarOfflineEncolaYReintentaConAck()
        {
            _red.Caida = true;
            Assert.False(await _f.EnviarTextoAsync("B002", "luego"));
            Assert.Single(_f.Pendientes());
            _red.Caida = false;
            Assert.Equal(1, await _f.ReintentarPendientesAsync());
            Assert.Empty(_f.Pendientes());
        }

        [Fact]
        public async Task FlushWorkerVaciaPendientesSinLlamadaExplicita()
        {
            _red.Caida = true;
            Assert.False(await _f.EnviarTextoAsync("B002", "luego"));
            Assert.Single(_f.Pendientes());
            _red.Caida = false;
            _f.DetenerFlushPendientes();
            _f.IniciarFlushPendientes(1);
            try
            {
                var limite = DateTime.UtcNow.AddSeconds(15);
                while (_f.Pendientes().Count != 0 && DateTime.UtcNow < limite)
                    await Task.Delay(100);
                Assert.Empty(_f.Pendientes());
            }
            finally
            {
                _f.DetenerFlushPendientes();
            }
        }

        [Fact]
        public async Task BroadcastPropioMarcadoConDestinoEspecial()
        {
            await _f.DifundirAsync("aviso");
            var propias = _f.CargarHistorial(FachadaCliente.DestinoBroadcast);
            Assert.Single(propias);
            Assert.Equal("aviso", propias[0]["contenido"]);
        }

        [Fact]
        public void BroadcastEntranteApareceEnConversacionConSuRemitente()
        {
            _red.Recibir(Doc(new Dictionary<string, object?>
                { { "tipo", "BROADCAST" }, { "id", "bc-1" }, { "remitente", "B002" },
                  { "contenido", "hola a todos" }, { "fechaHora", "2026-01-01T10:00:00" } }));
            var conv = _f.CargarHistorial("B002");
            Assert.Single(conv);
            Assert.Equal("hola a todos", conv[0]["contenido"]);
            Assert.Equal("B002", conv[0]["origen"]);
            Assert.Single(_avisos);
        }

        [Fact]
        public async Task ConectarConservaLaConexionInyectada()
        {
            await _f.ConectarAsync();
            Assert.Equal(1, _red.Conexiones);
        }

        [Fact]
        public void PresenciaPushSeExponeALaUi()
        {
            bool notificada = false;
            _f.OnPresencia += _ => notificada = true;
            _red.Recibir(Doc(new Dictionary<string, object?>
            {
                { "tipo", "PRESENCIA" }, { "codigo", "B002" },
                { "contenido", "conectado" }, { "usuariosConectados", new[] { "A001", "B002" } }
            }));
            Assert.True(notificada);
        }

        [Fact]
        public void ImagenEntranteNoGuardaBase64()
        {
            _red.Recibir(Doc(new Dictionary<string, object?>
                { { "tipo", "MENSAJE_IMAGEN" }, { "id", "im-1" }, { "remitente", "B002" },
                  { "contenidoImagen", "QUJD" }, { "nombreArchivo", "f.png" },
                  { "hashSha256", "hi" }, { "fechaHora", "2026-01-01T10:00:00" } }));
            var conv = _f.CargarHistorial("B002");
            Assert.False(conv[0].ContainsKey("contenido"));
        }

        private static Dictionary<string, JsonElement> Doc(Dictionary<string, object?> obj)
        {
            var json = JsonSerializer.Serialize(obj);
            using var doc = JsonDocument.Parse(json);
            var dict = new Dictionary<string, JsonElement>();
            foreach (var p in doc.RootElement.EnumerateObject())
                dict[p.Name] = p.Value.Clone();
            return dict;
        }

        private class RedFalsa : IConexionRed
        {
            public bool Caida { get; set; }
            public int Conexiones { get; private set; }
            public List<Dictionary<string, object?>> Peticiones { get; } = new();
            public event Action<Dictionary<string, JsonElement>>? OnMensajeRecibido;
            public event Action<Dictionary<string, JsonElement>>? OnCierre;
            public event Action? OnDesconectado;

            public void Recibir(Dictionary<string, JsonElement> msg) =>
                OnMensajeRecibido?.Invoke(msg);

            public Task ConectarAsync(string host, int puerto)
            {
                Conexiones++;
                return Task.CompletedTask;
            }
            public void Desconectar() { }
            public void Enviar(Dictionary<string, object?> msg)
            {
                if (Caida) throw new System.IO.IOException("corte simulado");
                Peticiones.Add(new Dictionary<string, object?>(msg));
            }

            public Task<Dictionary<string, JsonElement>> Pedir(Dictionary<string, object?> msg) =>
                Pedir(msg, TimeSpan.FromSeconds(5));

            public Task<Dictionary<string, JsonElement>> Pedir(Dictionary<string, object?> msg,
                TimeSpan timeout)
            {
                if (Caida) throw new IOExceptionShim();
                Peticiones.Add(new Dictionary<string, object?>(msg));
                string tipo = (string?)msg.GetValueOrDefault("tipo") ?? "";
                string? id = msg.GetValueOrDefault("id")?.ToString();
                return Task.FromResult(tipo switch
                {
                    "LOGIN" => Doc(new Dictionary<string, object?>
                        { { "tipo", "LOGIN_RESPUESTA" }, { "id", id }, { "exito", true } }),
                    "MENSAJE_TEXTO" when (string?)msg.GetValueOrDefault("destinatario") == "ZZZ" =>
                        Doc(new Dictionary<string, object?>
                            { { "tipo", "ERROR" }, { "id", id }, { "mensajeError", "destinatario inexistente" } }),
                    "MENSAJE_TEXTO" => Doc(new Dictionary<string, object?>
                        { { "tipo", "ACK" }, { "id", id }, { "exito", true },
                          { "hashSha256", "h" }, { "numCaracteres", 4 }, { "numPalabras", 1 } }),
                    "MENSAJE_IMAGEN" => Doc(new Dictionary<string, object?>
                        { { "tipo", "IMAGE_FILTERED_RESULT" }, { "id", id }, { "exito", true },
                          { "hashSha256", "hi" }, { "etapasFiltrado", "[]" }, { "archivoId", "7" } }),
                    "BROADCAST" => Doc(new Dictionary<string, object?>
                        { { "tipo", "ACK" }, { "id", id }, { "exito", true } }),
                    "LISTAR_CONECTADOS" => Doc(new Dictionary<string, object?>
                        { { "tipo", "LISTAR_CONECTADOS_RESPUESTA" }, { "id", id },
                          { "usuariosConectados", new[] { "A001", "B002" } } }),
                    _ => Doc(new Dictionary<string, object?>
                        { { "tipo", "ERROR" }, { "mensajeError", "no soportado en falsa" } })
                });
            }

            private static Dictionary<string, JsonElement> Doc(Dictionary<string, object?> obj) =>
                FachadaTests.Doc(obj);

            private sealed class IOExceptionShim : System.IO.IOException
            {
                public IOExceptionShim() : base("corte simulado") { }
            }
        }
    }
}
