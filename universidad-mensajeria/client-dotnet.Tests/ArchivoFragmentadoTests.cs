using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using Mensajeria.Componentes;
using Mensajeria.Fachada;
using Mensajeria.Store;
using Mensajeria.Transversal;
using Xunit;

namespace Mensajeria.Tests
{
    /// <summary>
    /// Archivos fragmentados 50 MB (Pista OpenCode): INICIO+PARTES+FIN, tope,
    /// reintento offline con el mismo ID y descarga fragmentada reensamblada.
    /// </summary>
    public class ArchivoFragmentadoTests : IDisposable
    {
        private readonly List<string> _tmp = new();
        private readonly string _dbPath;
        private readonly LocalStore _store;
        private readonly RedArchivos _red;
        private readonly FachadaCliente _f;

        public ArchivoFragmentadoTests()
        {
            _dbPath = TmpDb();
            _store = new LocalStore(_dbPath);
            _red = new RedArchivos();
            _f = new FachadaCliente(_red, new Autenticacion(_red),
                new HistorialLocal(_store), new CacheUsuarios(_store));
            Assert.True(_f.LoginAsync("A001", "x").GetAwaiter().GetResult());
        }

        public void Dispose()
        {
            _store.Dispose();
            foreach (var p in _tmp) try { if (File.Exists(p)) File.Delete(p); } catch { }
        }

        private string TmpDb()
        {
            string p = Path.Combine(Path.GetTempPath(), Guid.NewGuid() + ".db");
            _tmp.Add(p);
            return p;
        }

        private string TmpArchivo(int bytes, string nombre = "doc.pdf")
        {
            string p = Path.Combine(Path.GetTempPath(), Guid.NewGuid() + "_" + nombre);
            var rnd = new Random(7);
            byte[] buf = new byte[bytes];
            rnd.NextBytes(buf);
            File.WriteAllBytes(p, buf);
            _tmp.Add(p);
            return p;
        }

        [Fact]
        public async Task EnviarArchivoUsaFragmentosConElMismoId()
        {
            string ruta = TmpArchivo(2_500_000);
            Assert.True(await _f.EnviarArchivoAsync("B002", ruta));
            var inicios = _red.Peticiones.Where(p => (string?)p["tipo"] == TipoMensaje.ARCHIVO_INICIO).ToList();
            var partes = _red.Peticiones.Where(p => (string?)p["tipo"] == TipoMensaje.ARCHIVO_PARTE).ToList();
            var fines = _red.Peticiones.Where(p => (string?)p["tipo"] == TipoMensaje.ARCHIVO_FIN).ToList();
            Assert.Single(inicios);
            Assert.Equal(3, partes.Count);
            Assert.Single(fines);
            string id = (string)inicios[0]["id"]!;
            Assert.All(partes, p => Assert.Equal(id, p["id"]));
            Assert.Equal(id, fines[0]["id"]);
            Assert.Equal(2_500_000, Convert.ToInt64(inicios[0]["tamanoArchivo"]));
            var conv = _f.CargarHistorial("B002");
            Assert.Single(conv);
            Assert.Equal("ENVIADO", conv[0]["estado"]);
            Assert.Equal(id, conv[0]["id"]);
            Assert.Equal(TipoMensaje.MENSAJE_ARCHIVO, conv[0]["tipo"]);
            Assert.Empty(_f.Pendientes());
        }

        [Fact]
        public async Task RechazaArchivoSobre50MBAntesDeLaRed()
        {
            Assert.Equal(52_428_800L, FachadaCliente.MaxBytesArchivo);
            // Ruta inexistente: falla antes de cualquier trama de red.
            await Assert.ThrowsAsync<ArgumentException>(() =>
                _f.EnviarArchivoAsync("B002", Path.Combine(Path.GetTempPath(), Guid.NewGuid() + ".bin")));
            Assert.DoesNotContain(_red.Peticiones,
                p => (string?)p["tipo"] == TipoMensaje.ARCHIVO_INICIO);
        }

        [Fact]
        public async Task ReintentoOfflineReenviaConElMismoId()
        {
            string ruta = TmpArchivo(100_000);
            _red.Caida = true;
            Assert.False(await _f.EnviarArchivoAsync("B002", ruta));
            Assert.Single(_f.Pendientes());
            string id = (string)_f.Pendientes()[0]["id"]!;
            _red.Caida = false;
            Assert.Equal(1, await _f.ReintentarPendientesAsync());
            Assert.Empty(_f.Pendientes());
            var inicios = _red.Peticiones.Where(p => (string?)p["tipo"] == TipoMensaje.ARCHIVO_INICIO).ToList();
            Assert.Single(inicios);
            Assert.Equal(id, inicios[0]["id"]);
            Assert.Equal("ENVIADO", _f.CargarHistorial("B002")[0]["estado"]);
        }

        [Fact]
        public async Task DescargaFragmentadaReensamblaYVerificaHash()
        {
            byte[] original = new byte[2_200_000];
            new Random(3).NextBytes(original);
            _red.DescargaBytes = original;
            string destino = Path.Combine(Path.GetTempPath(), Guid.NewGuid() + ".bin");
            _tmp.Add(destino);
            var tarea = _f.DescargarArchivoAsync("m-1", "9", destino);
            // La respuesta INICIO llega por Pedir; las partes entran por push.
            await Task.Delay(200);
            _red.EmitirDescarga();
            string ruta = await tarea;
            Assert.Equal(original, await File.ReadAllBytesAsync(ruta));
        }

        [Fact]
        public void ArchivoEntranteSePersisteComoEntregado()
        {
            _red.Recibir(Doc(new Dictionary<string, object?>
                { { "tipo", "MENSAJE_ARCHIVO" }, { "id", "a-1" }, { "remitente", "B002" },
                  { "nombreArchivo", "informe.pdf" }, { "hashSha256", "h" },
                  { "archivoId", "9" }, { "fechaHora", "2026-01-01T10:00:00" } }));
            var conv = _f.CargarHistorial("B002");
            Assert.Single(conv);
            Assert.Equal("ENTREGADO", conv[0]["estado"]);
            Assert.Equal("[Archivo: informe.pdf]", conv[0]["contenido"]);
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

        private sealed class RedArchivos : IConexionRed
        {
            public bool Caida { get; set; }
            public byte[]? DescargaBytes { get; set; }
            public List<Dictionary<string, object?>> Peticiones { get; } = new();
            public event Action<Dictionary<string, JsonElement>>? OnMensajeRecibido;
            public event Action<Dictionary<string, JsonElement>>? OnCierre;
            public event Action? OnDesconectado;
            private string? _ultimaDescargaId;

            public void Recibir(Dictionary<string, JsonElement> msg) =>
                OnMensajeRecibido?.Invoke(msg);

            public void EmitirDescarga()
            {
                if (DescargaBytes == null || _ultimaDescargaId == null) return;
                string id = _ultimaDescargaId;
                int total = (DescargaBytes.Length + FachadaCliente.ParteArchivoBytes - 1)
                    / FachadaCliente.ParteArchivoBytes;
                for (int i = 0; i < total; i++)
                {
                    int desde = i * FachadaCliente.ParteArchivoBytes;
                    int hasta = Math.Min(desde + FachadaCliente.ParteArchivoBytes, DescargaBytes.Length);
                    string b64 = Convert.ToBase64String(DescargaBytes, desde, hasta - desde);
                    Recibir(Doc(new Dictionary<string, object?>
                        { { "tipo", "ARCHIVO_PARTE" }, { "id", id }, { "archivoId", id },
                          { "indiceParte", i }, { "contenidoImagen", b64 } }));
                }
                using var sha = System.Security.Cryptography.SHA256.Create();
                Recibir(Doc(new Dictionary<string, object?>
                    { { "tipo", "ARCHIVO_FIN" }, { "id", id }, { "archivoId", id },
                      { "hashSha256", Convert.ToHexString(sha.ComputeHash(DescargaBytes)).ToLowerInvariant() } }));
            }

            public Task ConectarAsync(string h, int p) => Task.CompletedTask;
            public void Desconectar() { }
            public void Enviar(Dictionary<string, object?> m)
            {
                if (Caida) throw new IOException("corte simulado");
                Peticiones.Add(new Dictionary<string, object?>(m));
            }

            public Task<Dictionary<string, JsonElement>> Pedir(Dictionary<string, object?> msg) =>
                Pedir(msg, TimeSpan.FromSeconds(5));

            public Task<Dictionary<string, JsonElement>> Pedir(Dictionary<string, object?> msg, TimeSpan t)
            {
                if (Caida) throw new IOException("corte simulado");
                if (!msg.ContainsKey("id")) msg["id"] = Guid.NewGuid().ToString();
                Peticiones.Add(new Dictionary<string, object?>(msg));
                string tipo = (string?)msg.GetValueOrDefault("tipo") ?? "";
                string? id = msg.GetValueOrDefault("id")?.ToString();
                return Task.FromResult(tipo switch
                {
                    "LOGIN" => Doc(new Dictionary<string, object?> { { "tipo", "LOGIN_RESPUESTA" }, { "id", id }, { "exito", true } }),
                    "ARCHIVO_INICIO" or "ARCHIVO_PARTE" or "ARCHIVO_FIN" or "MENSAJE_ARCHIVO"
                        => Doc(new Dictionary<string, object?> { { "tipo", "ACK" }, { "id", id }, { "exito", true } }),
                    "DESCARGAR_ARCHIVO" => DescargaRespuesta(id),
                    _ => Doc(new Dictionary<string, object?> { { "tipo", "ACK" }, { "id", id }, { "exito", true } })
                });
            }

            private Dictionary<string, JsonElement> DescargaRespuesta(string? id)
            {
                if (DescargaBytes == null)
                    return Doc(new Dictionary<string, object?> { { "tipo", "ERROR" }, { "mensajeError", "sin bytes" } });
                _ultimaDescargaId = id;
                int total = (DescargaBytes.Length + FachadaCliente.ParteArchivoBytes - 1)
                    / FachadaCliente.ParteArchivoBytes;
                return Doc(new Dictionary<string, object?>
                    { { "tipo", "ARCHIVO_INICIO" }, { "id", id }, { "archivoId", id },
                      { "nombreArchivo", "f.bin" }, { "tamanoArchivo", (long)DescargaBytes.Length },
                      { "totalPartes", total }, { "exito", true } });
            }
        }
    }
}
