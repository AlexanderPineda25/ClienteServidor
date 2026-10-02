using System;
using System.Collections.Generic;
using System.IO;
using Mensajeria.Componentes;
using Mensajeria.Store;
using Xunit;

namespace Mensajeria.Tests
{
    /// <summary>
    /// DAO local sobre SQLite con fixture compartida (§13.9): mismas filas que
    /// verifican H2 (Java) y sqlite3 (Python), mismos resultados esperados.
    /// </summary>
    public class HistorialLocalTests : IDisposable
    {
        private readonly string _dbPath;
        private readonly LocalStore _store;
        private readonly HistorialLocal _h;
        private readonly CacheUsuarios _cache;

        public HistorialLocalTests()
        {
            _dbPath = Path.Combine(Path.GetTempPath(), Guid.NewGuid() + ".db");
            _store = new LocalStore(_dbPath);
            _h = new HistorialLocal(_store);
            _cache = new CacheUsuarios(_store);
            // Fixture compartida.
            Upsert("m1", "A", "B", "MENSAJE_TEXTO", "hola", "h1", 4, 1, null, null, null,
                "2026-01-01T10:00:00", 1, 1);
            Upsert("m2", "B", "A", "MENSAJE_TEXTO", "que tal", "h2", 7, 2, null, null, null,
                "2026-01-01T10:05:00", 0, 1);
            Upsert("m3", "A", "C", "MENSAJE_IMAGEN", "[Imagen]", "h3", null, null, "f.png",
                null, 12, "2026-01-01T10:10:00", 1, 0);
            _cache.Guardar("A", "Ana", "Lopez", "Sistemas", 1, "2026-01-01", "2026-01-02T10:00:00");
            _cache.Guardar("B", "Luis", "Garcia", "Sistemas", 0, "2026-01-01", "2026-01-02T10:00:00");
        }

        private void Upsert(string id, string ori, string des, string tip, string? con,
            string? hash, int? nca, int? npa, string? nar, string? rar, long? tar,
            string fecha, int env, int desca) =>
            _h.UpsertMensaje(id, ori, des, tip, con, hash, nca, npa, nar, rar, tar, fecha, env, desca);

        public void Dispose()
        {
            _store.Dispose();
            try { File.Delete(_dbPath); } catch { }
        }

        [Fact]
        public void PaginaAmbosSentidosRecientePrimero()
        {
            var pagina = _h.PaginaConversacion("A", "B", 50, 0);
            Assert.Equal(new[] { "m2", "m1" },
                new[] { (string)pagina[0]["id"], (string)pagina[1]["id"] });
            Assert.Equal(2, _h.ContarConversacion("A", "B"));
            Assert.Equal(1, _h.ContarConversacion("A", "C"));
        }

        [Fact]
        public void UpsertSobreescribeMismoId()
        {
            Upsert("m1", "A", "B", "MENSAJE_TEXTO", "editado", "h9", 7, 1, null, null,
                null, "2026-01-01T10:00:00", 1, 1);
            var pagina = _h.PaginaConversacion("A", "B", 50, 0);
            Assert.Equal(2, pagina.Count);
            Assert.Equal("editado", pagina[1]["contenido"]);
        }

        [Fact]
        public void MarcarDescargadoGuardaRuta()
        {
            _h.MarcarDescargado("m3", "/tmp/f.png");
            var fila = _h.PaginaConversacion("A", "C", 50, 0)[0];
            Assert.Equal(1L, fila["descargado"]);
            Assert.Equal("/tmp/f.png", fila["rutaArchivo"]);
        }

        [Fact]
        public void PaginaNoCargaContenidoDeImagen()
        {
            _h.UpsertMensaje("imagen-base64", "A", "B", "MENSAJE_IMAGEN", "aGVsbG8=",
                null, null, null, "foto.png", null, null, "2026-01-01T10:00:00", 0, 0);
            var fila = _h.PaginaConversacion("A", "B", 50, 0)
                .Find(m => (string)m["id"] == "imagen-base64");
            Assert.NotNull(fila);
            Assert.False(fila!.ContainsKey("contenido"));
            Assert.Equal("aGVsbG8=", _h.ContenidoPorId("imagen-base64"));
        }

        [Fact]
        public void PendientesConPredeleteEIntentos()
        {
            _h.RegistrarPendiente("p1", "MENSAJE_TEXTO", "A", "B", "hola", null, null,
                "2026-01-01T10:00:00");
            // Re-registro del mismo id: upsert, no excepcion.
            _h.RegistrarPendiente("p1", "MENSAJE_TEXTO", "A", "B", "hola", null, null,
                "2026-01-01T10:00:00");
            Assert.Single(_h.ListarPendientes());
            _h.IncrementarIntento("p1", "sin red");
            var pend = _h.ListarPendientes()[0];
            Assert.Equal(1L, pend["intentos"]);
            Assert.Equal("sin red", pend["ultimoError"]);
            _h.EliminarPendiente("p1");
            Assert.Empty(_h.ListarPendientes());
        }

        [Fact]
        public void CacheConectadosPrimero()
        {
            var filas = _cache.Listar();
            Assert.Equal("A", filas[0]["codigo"]);
            _cache.MarcarConectados(new List<string> { "B" });
            filas = _cache.Listar();
            Assert.Equal("B", filas[0]["codigo"]);
            Assert.NotNull(_cache.PorCodigo("A"));
            Assert.Null(_cache.PorCodigo("ZZZ"));
        }
    }
}
