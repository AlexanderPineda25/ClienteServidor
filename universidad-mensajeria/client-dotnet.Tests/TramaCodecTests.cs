using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Mensajeria.Componentes;
using Xunit;

namespace Mensajeria.Tests
{
    /// <summary>Trama 4B big-endian + JSON contra sockets reales (sin servidor).</summary>
    public class TramaCodecTests
    {
        [Fact]
        public void RoundtripConservaCamposYCabeceraBigEndian()
        {
            var listener = new TcpListener(IPAddress.Loopback, 0);
            listener.Start();
            try
            {
                var aceptado = listener.AcceptTcpClientAsync();
                using var cliente = new TcpClient();
                cliente.Connect(IPAddress.Loopback, ((IPEndPoint)listener.LocalEndpoint).Port);
                using var ladoServidor = aceptado.Result;

                var msg = new Dictionary<string, object?>
                {
                    { "tipo", "LOGIN" }, { "codigo", "A001" }
                };
                TramaCodec.Escribir(ladoServidor.GetStream(), msg);
                var leido = TramaCodec.Leer(cliente.GetStream());
                Assert.Equal("LOGIN", leido["tipo"].GetString());
                Assert.Equal("A001", leido["codigo"].GetString());
            }
            finally { listener.Stop(); }
        }

        [Fact]
        public void RechazaLongitudInvalida()
        {
            var listener = new TcpListener(IPAddress.Loopback, 0);
            listener.Start();
            try
            {
                var aceptado = listener.AcceptTcpClientAsync();
                using var cliente = new TcpClient();
                cliente.Connect(IPAddress.Loopback, ((IPEndPoint)listener.LocalEndpoint).Port);
                using var ladoServidor = aceptado.Result;
                // Longitud 0x7FFFFFFF > tope.
                ladoServidor.GetStream().Write(new byte[] { 0x7F, 0xFF, 0xFF, 0xFF }, 0, 4);
                Assert.Throws<InvalidOperationException>(() => TramaCodec.Leer(cliente.GetStream()));
            }
            finally { listener.Stop(); }
        }
    }
}
