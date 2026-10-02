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
    /// <summary>
    /// Per-cuenta SQLite, migración idempotente, persistencia previa al envío y
    /// flujo entrante Java/Python. Alcance OpenCode (.NET) del plan de reparación.
    /// </summary>
    public class CuentaStoreTests : IDisposable
    {
        private readonly List<string> _tmp = new();

        private string TmpDb(string? nombre = null)
        {
            string p = Path.Combine(Path.GetTempPath(), (nombre ?? Guid.NewGuid().ToString()) + ".db");
            _tmp.Add(p);
            foreach (var suf in new[] { "-wal", "-shm", "-journal" })
                _tmp.Add(p + suf);
            return p;
        }

        public void Dispose()
        {
            foreach (var p in _tmp) try { if (File.Exists(p)) File.Delete(p); } catch { }
        }

        [Fact]
        public void RutaPorCuentaInsertaSufijoAntesDeExtension()
        {
            string r = LocalStore.RutaParaCuenta("sqlite:///~/mensajeria_cliente.db", "A001234567");
            Assert.EndsWith("mensajeria_cliente_A001234567.db", r.Replace('\\', '/'));
            // Idempotente: no duplica sufijo.
            string r2 = LocalStore.RutaParaCuenta(r, "A001234567");
            Assert.Equal(r, r2);
            // Cuentas distintas => archivos distintos.
            string otro = LocalStore.RutaParaCuenta("sqlite:///~/mensajeria_cliente.db", "B009");
            Assert.NotEqual(r, otro);
        }

        [Fact]
        public void MigracionRecalculaEnviadoYEsIdempotente()
        {
            string legado = TmpDb("legado");
            using (var store = new LocalStore(legado))
            {
                var h = new HistorialLocal(store);
                // Perspectiva mezclada en el archivo legado (bug original).
                h.UpsertMensaje("m1", "A001", "B002", "MENSAJE_TEXTO", "hola",
                    null, null, null, null, null, null, "2026-01-01T10:00:00", 0, 1, "ENTREGADO");
                h.RegistrarPendiente("p-a", "MENSAJE_TEXTO", "A001", "B002", "hola", null, null, "2026-01-01T10:00:00");
                h.RegistrarPendiente("p-b", "MENSAJE_TEXTO", "B002", "A001", "otro", null, null, "2026-01-01T10:00:00");
            }
            string cuentaA = TmpDb("cuentaA");
            LocalStore.MigrarLegadoACuenta(legado, cuentaA, "A001");
            // Repetir no duplica.
            LocalStore.MigrarLegadoACuenta(legado, cuentaA, "A001");

            using (var store = new LocalStore(cuentaA))
            {
                var h = new HistorialLocal(store);
                var conv = h.PaginaConversacion("A001", "B002", 50, 0);
                Assert.Single(conv);
                //Origen A001 => enviado=1 para la cuenta A001.
                Assert.Equal(1L, conv[0]["enviado"]);
                // Pendientes repartidos por origen: solo p-a.
                var pends = h.ListarPendientes();
                Assert.Single(pends);
                Assert.Equal("p-a", pends[0]["id"]);
            }
            // Legado intacto como respaldo.
            Assert.True(File.Exists(legado));
            using (var store = new LocalStore(legado))
            {
                var h = new HistorialLocal(store);
                Assert.Equal(1, h.ContarConversacion("A001", "B002"));
            }
        }

        [Fact]
        public void DosProcesosMismaCuentaCompartenArchivo()
        {
            string cuenta = TmpDb("compartida");
            using (var a = new LocalStore(cuenta))
            using (var b = new LocalStore(cuenta))
            {
                var ha = new HistorialLocal(a);
                var hb = new HistorialLocal(b);
                ha.UpsertMensaje("x1", "A001", "B002", "MENSAJE_TEXTO", "uno",
                    null, null, null, null, null, null, "2026-01-01T10:00:00", 1, 1, "ENVIADO");
                // El segundo proceso ve la fila del primero sin bloqueo.
                Assert.Equal(1, hb.ContarConversacion("A001", "B002"));
            }
        }

        [Fact]
        public async Task AcuseDuranteEsperaNoSePierde()
        {
            string db = TmpDb();
            using var store = new LocalStore(db);
            var hist = new HistorialLocal(store);
            var cache = new CacheUsuarios(store);
            var red = new RedCarrera(hist);
            var f = new FachadaCliente(red, new Autenticacion(red), hist, cache);
            Assert.True(await f.LoginAsync("A001", "x"));
            Assert.True(await f.EnviarTextoAsync("B002", "hola"));
            var conv = f.CargarHistorial("B002");
            Assert.Single(conv);
            // El acuse ENTREGADO inyectado a mitad del Pedir sobrevivió al Upsert ENVIADO.
            Assert.Equal("ENTREGADO", conv[0]["estado"]);
        }

        [Fact]
        public async Task RechazaDestinoPropio()
        {
            string db = TmpDb();
            using var store = new LocalStore(db);
            var hist = new HistorialLocal(store);
            var cache = new CacheUsuarios(store);
            var red = new RedSimple();
            var f = new FachadaCliente(red, new Autenticacion(red), hist, cache);
            Assert.True(await f.LoginAsync("A001", "x"));
            await Assert.ThrowsAsync<ArgumentException>(() => f.EnviarTextoAsync("A001", "yo"));
        }

        [Fact]
        public void EntranteDeJavaGuardaEntregadoSinBase64()
        {
            string db = TmpDb();
            using var store = new LocalStore(db);
            var hist = new HistorialLocal(store);
            var cache = new CacheUsuarios(store);
            var red = new RedSimple();
            var f = new FachadaCliente(red, new Autenticacion(red), hist, cache);
            f.LoginAsync("A001", "x").GetAwaiter().GetResult();
            // Texto estilo Java.
            red.Recibir(Doc(new Dictionary<string, object?>
                { { "tipo", "MENSAJE_TEXTO" }, { "id", "j-t1" }, { "remitente", "B002" },
                  { "contenido", "hola desde java" }, { "fechaHora", "2026-01-01T10:00:00" } }));
            // Imagen estilo Python (.NET/Java comparten archivoId/hash).
            red.Recibir(Doc(new Dictionary<string, object?>
                { { "tipo", "MENSAJE_IMAGEN" }, { "id", "p-i1" }, { "remitente", "B002" },
                  { "contenidoImagen", "QUJD" }, { "nombreArchivo", "f.png" },
                  { "hashSha256", "himg" }, { "archivoId", "9" }, { "fechaHora", "2026-01-01T10:01:00" } }));
            var conv = f.CargarHistorial("B002");
            Assert.Equal(2, conv.Count);
            foreach (var row in conv)
            {
                Assert.Equal("ENTREGADO", row["estado"]);
                Assert.Equal(0L, row["enviado"]);
            }
            var img = conv.First(r => (string)r["id"] == "p-i1");
            Assert.False(img.ContainsKey("contenido"));
            Assert.Equal("9", img["archivoId"]);
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

        private class RedSimple : IConexionRed
        {
            public event Action<Dictionary<string, JsonElement>>? OnMensajeRecibido;
            public event Action<Dictionary<string, JsonElement>>? OnCierre;
            public event Action? OnDesconectado;
            public void Recibir(Dictionary<string, JsonElement> m) => OnMensajeRecibido?.Invoke(m);
            public Task ConectarAsync(string h, int p) => Task.CompletedTask;
            public void Desconectar() { }
            public void Enviar(Dictionary<string, object?> m) { }
            public Task<Dictionary<string, JsonElement>> Pedir(Dictionary<string, object?> m) =>
                Pedir(m, TimeSpan.FromSeconds(5));
            public Task<Dictionary<string, JsonElement>> Pedir(Dictionary<string, object?> m, TimeSpan t)
            {
                string tipo = (string?)m.GetValueOrDefault("tipo") ?? "";
                string? id = m.GetValueOrDefault("id")?.ToString();
                return Task.FromResult(tipo switch
                {
                    "LOGIN" => Doc(new Dictionary<string, object?> { { "tipo", "LOGIN_RESPUESTA" }, { "id", id }, { "exito", true } }),
                    "MENSAJE_TEXTO" => Doc(new Dictionary<string, object?> { { "tipo", "ACK" }, { "id", id }, { "exito", true } }),
                    "MENSAJE_IMAGEN" => Doc(new Dictionary<string, object?> { { "tipo", "IMAGE_FILTERED_RESULT" }, { "id", id }, { "exito", true }, { "archivoId", "7" } }),
                    _ => Doc(new Dictionary<string, object?> { { "tipo", "ACK" }, { "id", id }, { "exito", true } })
                });
            }
        }

        /// <summary>Simula la carrera: a mitad del Pedir entra el push ENTREGADO por el mismo ID.</summary>
        private class RedCarrera : IConexionRed
        {
            private readonly IHistorialLocal _hist;
            public RedCarrera(IHistorialLocal hist) { _hist = hist; }
            public event Action<Dictionary<string, JsonElement>>? OnMensajeRecibido;
            public event Action<Dictionary<string, JsonElement>>? OnCierre;
            public event Action? OnDesconectado;
            public Task ConectarAsync(string h, int p) => Task.CompletedTask;
            public void Desconectar() { }
            public void Enviar(Dictionary<string, object?> m) { }
            public async Task<Dictionary<string, JsonElement>> Pedir(Dictionary<string, object?> m) =>
                await Pedir(m, TimeSpan.FromSeconds(5));
            public async Task<Dictionary<string, JsonElement>> Pedir(Dictionary<string, object?> m, TimeSpan t)
            {
                string? id = m.GetValueOrDefault("id")?.ToString();
                string tipo = (string?)m.GetValueOrDefault("tipo") ?? "";
                if (tipo == "LOGIN")
                    return Doc(new Dictionary<string, object?> { { "tipo", "LOGIN_RESPUESTA" }, { "id", id }, { "exito", true } });
                // El push llega ANTES de que el ACK complete (fila PENDIENTE ya existe).
                await Task.Delay(10);
                _hist.MarcarEstado(id!, "ENTREGADO");
                await Task.Delay(10);
                return Doc(new Dictionary<string, object?> { { "tipo", "ACK" }, { "id", id }, { "exito", true } });
            }
        }
    }
}
