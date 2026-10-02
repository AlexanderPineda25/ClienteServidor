using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Net;
using System.Net.Sockets;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Mensajeria.Componentes;
using Xunit;

namespace Mensajeria.Tests
{
    /// <summary>Demultiplexor por id contra stub guionado (sin servidor real).</summary>
    public class ConexionRedTests : IDisposable
    {
        private readonly TcpListener _listener;
        private readonly Thread _hilo;
        private readonly ConcurrentQueue<Dictionary<string, object?>> _empujes = new();
        private readonly List<Dictionary<string, JsonElement>> _recibidos = new();
        private readonly List<Dictionary<string, JsonElement>> _cierres = new();
        private readonly ConexionRed _cn;
        private volatile bool _seguir = true;

        public ConexionRedTests()
        {
            _listener = new TcpListener(IPAddress.Loopback, 0);
            _listener.Start();
            _hilo = new Thread(BucleStub) { IsBackground = true };
            _hilo.Start();
            int puerto = ((IPEndPoint)_listener.LocalEndpoint).Port;
            _cn = new ConexionRed();
            _cn.OnMensajeRecibido += m => { lock (_recibidos) _recibidos.Add(m); };
            _cn.OnCierre += m => { lock (_cierres) _cierres.Add(m); };
            _cn.ConectarAsync("127.0.0.1", puerto).GetAwaiter().GetResult();
        }

        public void Dispose()
        {
            _seguir = false;
            _cn.Desconectar();
            _listener.Stop();
        }

        private void BucleStub()
        {
            try
            {
                using var cli = _listener.AcceptTcpClient();
                cli.ReceiveTimeout = 10000;
                var stream = cli.GetStream();
                while (_seguir)
                {
                    if (stream.DataAvailable)
                    {
                        Dictionary<string, JsonElement> req;
                        try { req = TramaCodec.Leer(stream); }
                        catch { break; }
                        string tipo = req["tipo"].GetString()!;
                        string? id = req.TryGetValue("id", out var e) ? e.GetString() : null;
                        if (tipo == "MUDO") continue; // cuelga a proposito (timeout)
                        if (tipo == "LOGIN")
                            TramaCodec.Escribir(stream, Resp(id, "LOGIN_RESPUESTA",
                                new Dictionary<string, object?> { { "exito", true } }));
                        else if (tipo == "MENSAJE_TEXTO")
                        {
                            Thread.Sleep(200); // ventana al empuje asincrono
                            TramaCodec.Escribir(stream, Resp(id, "ACK",
                                new Dictionary<string, object?> { { "exito", true } }));
                        }
                        else if (tipo == "LISTAR_CONECTADOS")
                            TramaCodec.Escribir(stream, Resp(id, "LISTAR_CONECTADOS_RESPUESTA",
                                new Dictionary<string, object?> { { "usuariosConectados", new[] { "A001", "B002" } } }));
                    }
                    else if (_empujes.TryDequeue(out var push))
                    {
                        try { TramaCodec.Escribir(stream, push); } catch { break; }
                    }
                    else Thread.Sleep(20);
                }
            }
            catch { }
        }

        private static Dictionary<string, object?> Resp(string? id, string tipo,
            Dictionary<string, object?> extra)
        {
            extra["tipo"] = tipo;
            extra["id"] = id;
            return extra;
        }

        private static bool Esperar(Func<bool> cond, int ms = 5000)
        {
            var fin = DateTime.UtcNow.AddMilliseconds(ms);
            while (DateTime.UtcNow < fin)
            {
                if (cond()) return true;
                Thread.Sleep(50);
            }
            return cond();
        }

        [Fact]
        public async Task PedirDevuelveRespuestaCorrelacionada()
        {
            var resp = await _cn.Pedir(new Dictionary<string, object?> { { "tipo", "LOGIN" } });
            Assert.Equal("LOGIN_RESPUESTA", resp["tipo"].GetString());
            Assert.True(resp["exito"].GetBoolean());
        }

        [Fact]
        public async Task EntregaAsincronaNoRompeElAck()
        {
            _empujes.Enqueue(new Dictionary<string, object?>
                { { "tipo", "BROADCAST" }, { "id", "empuje-1" },
                  { "remitente", "C" }, { "contenido", "aviso" } });
            var ack = await _cn.Pedir(new Dictionary<string, object?> { { "tipo", "MENSAJE_TEXTO" } });
            Assert.Equal("ACK", ack["tipo"].GetString());
            Assert.True(Esperar(() =>
            {
                lock (_recibidos) return _recibidos.Exists(m =>
                    m["tipo"].GetString() == "BROADCAST");
            }));
        }

        [Fact]
        public void CloseNoticeVaAlCallbackAunqueTengaId()
        {
            // Carrera del KICK: el aviso viaja con id y el ACK llegaria despues.
            _empujes.Enqueue(new Dictionary<string, object?>
                { { "tipo", "CLOSE_NOTICE" }, { "id", "kick-1" },
                  { "mensajeError", "kick" } });
            Assert.True(Esperar(() => { lock (_cierres) return _cierres.Count == 1; }));
            lock (_cierres)
                Assert.Equal("kick", _cierres[0]["mensajeError"].GetString());
        }

        [Fact]
        public async Task PedidosConcurrentesCorrelacionan()
        {
            var tareas = new List<Task<Dictionary<string, JsonElement>>>();
            for (int i = 0; i < 10; i++)
                tareas.Add(_cn.Pedir(new Dictionary<string, object?> { { "tipo", "LISTAR_CONECTADOS" } }));
            var resps = await Task.WhenAll(tareas);
            foreach (var r in resps)
            {
                Assert.Equal("LISTAR_CONECTADOS_RESPUESTA", r["tipo"].GetString());
                Assert.Equal(2, r["usuariosConectados"].GetArrayLength());
            }
        }

        [Fact]
        public async Task TimeoutSinRespuesta()
        {
            await Assert.ThrowsAsync<TimeoutException>(() =>
                _cn.Pedir(new Dictionary<string, object?> { { "tipo", "MUDO" } },
                    TimeSpan.FromSeconds(1)));
        }
    }
}
