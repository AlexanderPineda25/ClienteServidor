using System;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Collections.Generic;
using System.IO;

namespace Mensajeria.Componentes
{
    public static class TramaCodec
    {
        public const int TamanoMaximoTrama = 16 * 1024 * 1024;

        public static void Escribir(System.IO.Stream stream, Dictionary<string, object?> mensaje)
        {
            var options = new JsonSerializerOptions { DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull };
            byte[] payload = JsonSerializer.SerializeToUtf8Bytes(mensaje, options);
            byte[] header = BitConverter.GetBytes(payload.Length);
            if (BitConverter.IsLittleEndian) Array.Reverse(header);
            stream.Write(header, 0, 4);
            stream.Write(payload, 0, payload.Length);
            stream.Flush();
        }

        public static Dictionary<string, JsonElement> Leer(System.IO.Stream stream)
        {
            byte[] header = LeerExacto(stream, 4);
            if (BitConverter.IsLittleEndian) Array.Reverse(header);
            int length = BitConverter.ToInt32(header, 0);
            if (length <= 0 || length > TamanoMaximoTrama)
                throw new InvalidOperationException($"Longitud invalida: {length}");
            byte[] payload = LeerExacto(stream, length);
            return JsonSerializer.Deserialize<Dictionary<string, JsonElement>>(payload)!;
        }

        private static byte[] LeerExacto(System.IO.Stream stream, int n)
        {
            byte[] buf = new byte[n];
            int offset = 0;
            while (offset < n)
            {
                int read = stream.Read(buf, offset, n - offset);
                if (read == 0) throw new IOException("Conexion cerrada");
                offset += read;
            }
            return buf;
        }
    }
}
