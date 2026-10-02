using System;
using System.Collections.Generic;
using Microsoft.Data.Sqlite;
using Mensajeria.Store;

namespace Mensajeria.Componentes
{
    /// <summary>
    /// DAO SQLite del historial (queries canonicas HISTORIAL_LOCAL.md, tal cual:
    /// upsert portable con DELETE + INSERT, sin sintaxis de motor).
    /// </summary>
    public class HistorialLocal : IHistorialLocal
    {
        private readonly LocalStore _store;

        public HistorialLocal(LocalStore store)
        {
            _store = store;
        }

        public void UpsertMensaje(string id, string origen, string destino, string tipo,
            string? contenido, string? hash, int? numCaracteres, int? numPalabras,
            string? nombreArchivo, string? rutaArchivo, long? tamanoArchivo,
            string fechaEnvio, int enviado, int descargado,
            string estado = "ENVIADO", string? archivoId = null)
        {
            using var cmd = _store.Connection.CreateCommand();
            cmd.CommandText = @"
INSERT INTO historial_local (
    id_mensaje, origen, destino, tipo, contenido, hash_sha256,
    num_caracteres, num_palabras, nombre_archivo, ruta_archivo,
    tamano_archivo, fecha_envio, enviado, descargado, estado, archivo_id
) VALUES (@id, @ori, @des, @tip, @con, @has, @nca, @npa, @nar, @rar, @tar, @fen, @env, @desca, @est, @aid)
ON CONFLICT(id_mensaje) DO UPDATE SET
    contenido = excluded.contenido,
    hash_sha256 = COALESCE(excluded.hash_sha256, historial_local.hash_sha256),
    num_caracteres = COALESCE(excluded.num_caracteres, historial_local.num_caracteres),
    num_palabras = COALESCE(excluded.num_palabras, historial_local.num_palabras),
    nombre_archivo = COALESCE(excluded.nombre_archivo, historial_local.nombre_archivo),
    ruta_archivo = COALESCE(excluded.ruta_archivo, historial_local.ruta_archivo),
    tamano_archivo = COALESCE(excluded.tamano_archivo, historial_local.tamano_archivo),
    archivo_id = COALESCE(excluded.archivo_id, historial_local.archivo_id),
    descargado = MAX(excluded.descargado, historial_local.descargado),
    estado = CASE
        WHEN historial_local.estado = 'LEIDO' OR excluded.estado = 'LEIDO' THEN 'LEIDO'
        WHEN historial_local.estado = 'ENTREGADO' AND excluded.estado IN ('PENDIENTE', 'ENVIADO') THEN 'ENTREGADO'
        WHEN historial_local.estado = 'ENVIADO' AND excluded.estado = 'PENDIENTE' THEN 'ENVIADO'
        ELSE excluded.estado END";
            cmd.Parameters.AddWithValue("@id", id);
            cmd.Parameters.AddWithValue("@ori", origen);
            cmd.Parameters.AddWithValue("@des", destino);
            cmd.Parameters.AddWithValue("@tip", tipo);
            cmd.Parameters.AddWithValue("@con", (object?)contenido ?? DBNull.Value);
            cmd.Parameters.AddWithValue("@has", (object?)hash ?? DBNull.Value);
            cmd.Parameters.AddWithValue("@nca", (object?)numCaracteres ?? DBNull.Value);
            cmd.Parameters.AddWithValue("@npa", (object?)numPalabras ?? DBNull.Value);
            cmd.Parameters.AddWithValue("@nar", (object?)nombreArchivo ?? DBNull.Value);
            cmd.Parameters.AddWithValue("@rar", (object?)rutaArchivo ?? DBNull.Value);
            cmd.Parameters.AddWithValue("@tar", (object?)tamanoArchivo ?? DBNull.Value);
            cmd.Parameters.AddWithValue("@fen", fechaEnvio);
            cmd.Parameters.AddWithValue("@env", enviado);
            cmd.Parameters.AddWithValue("@desca", descargado);
            cmd.Parameters.AddWithValue("@est", estado);
            cmd.Parameters.AddWithValue("@aid", (object?)archivoId ?? DBNull.Value);
            cmd.ExecuteNonQuery();
        }

        public List<Dictionary<string, object>> PaginaConversacion(string origen, string destino, int limit, int offset)
        {
            var result = new List<Dictionary<string, object>>();
            using var cmd = _store.Connection.CreateCommand();
            cmd.CommandText = @"
SELECT id_mensaje, origen, destino, tipo,
       CASE WHEN tipo = 'MENSAJE_IMAGEN' THEN NULL ELSE contenido END AS contenido,
       hash_sha256,
       num_caracteres, num_palabras, nombre_archivo, ruta_archivo,
       tamano_archivo, fecha_envio, enviado, descargado, estado, archivo_id
FROM historial_local
WHERE (origen = @o1 AND destino = @d1) OR (origen = @o2 AND destino = @d2)
ORDER BY fecha_envio DESC
LIMIT @lim OFFSET @off";
            cmd.Parameters.AddWithValue("@o1", origen);
            cmd.Parameters.AddWithValue("@d1", destino);
            cmd.Parameters.AddWithValue("@o2", destino);
            cmd.Parameters.AddWithValue("@d2", origen);
            cmd.Parameters.AddWithValue("@lim", limit);
            cmd.Parameters.AddWithValue("@off", offset);

            using var reader = cmd.ExecuteReader();
            while (reader.Read())
            {
                var row = new Dictionary<string, object>
                {
                    { "id", reader.GetString(0) },
                    { "origen", reader.GetString(1) },
                    { "destino", reader.GetString(2) },
                    { "tipo", reader.GetString(3) },
                    { "fechaEnvio", reader.GetString(11) },
                    { "enviado", reader.GetInt64(12) },
                    { "descargado", reader.GetInt64(13) }
                };
                row["estado"] = reader.GetString(14);
                if (!reader.IsDBNull(4)) row["contenido"] = reader.GetString(4);
                if (!reader.IsDBNull(5)) row["hash"] = reader.GetString(5);
                if (!reader.IsDBNull(6)) row["numCaracteres"] = reader.GetInt64(6);
                if (!reader.IsDBNull(7)) row["numPalabras"] = reader.GetInt64(7);
                if (!reader.IsDBNull(8)) row["nombreArchivo"] = reader.GetString(8);
                if (!reader.IsDBNull(9)) row["rutaArchivo"] = reader.GetString(9);
                if (!reader.IsDBNull(10)) row["tamanoArchivo"] = reader.GetInt64(10);
                if (!reader.IsDBNull(15)) row["archivoId"] = reader.GetString(15);
                result.Add(row);
            }
            return result;
        }

        public string? ContenidoPorId(string idMensaje)
        {
            using var cmd = _store.Connection.CreateCommand();
            cmd.CommandText = "SELECT contenido FROM historial_local WHERE id_mensaje = @id";
            cmd.Parameters.AddWithValue("@id", idMensaje);
            return cmd.ExecuteScalar() as string;
        }

        public int ContarConversacion(string origen, string destino)
        {
            using var cmd = _store.Connection.CreateCommand();
            cmd.CommandText = @"
SELECT COUNT(*)
FROM historial_local
WHERE (origen = @o1 AND destino = @d1) OR (origen = @o2 AND destino = @d2)";
            cmd.Parameters.AddWithValue("@o1", origen);
            cmd.Parameters.AddWithValue("@d1", destino);
            cmd.Parameters.AddWithValue("@o2", destino);
            cmd.Parameters.AddWithValue("@d2", origen);
            return Convert.ToInt32(cmd.ExecuteScalar());
        }

        public void MarcarDescargado(string idMensaje, string rutaArchivo)
        {
            using var cmd = _store.Connection.CreateCommand();
            cmd.CommandText = @"
UPDATE historial_local
SET descargado = 1, ruta_archivo = @rar
WHERE id_mensaje = @id";
            cmd.Parameters.AddWithValue("@rar", rutaArchivo);
            cmd.Parameters.AddWithValue("@id", idMensaje);
            cmd.ExecuteNonQuery();
        }

        public void ActualizarArchivoId(string idMensaje, string archivoId)
        {
            using var cmd = _store.Connection.CreateCommand();
            cmd.CommandText = "UPDATE historial_local SET archivo_id = @aid WHERE id_mensaje = @id";
            cmd.Parameters.AddWithValue("@aid", archivoId);
            cmd.Parameters.AddWithValue("@id", idMensaje);
            cmd.ExecuteNonQuery();
        }

        public void MarcarEstado(string idMensaje, string estado)
        {
            using var cmd = _store.Connection.CreateCommand();
            cmd.CommandText = @"UPDATE historial_local SET estado = CASE
                WHEN estado = 'LEIDO' OR @est = 'LEIDO' THEN 'LEIDO'
                WHEN estado = 'ENTREGADO' AND @est IN ('PENDIENTE', 'ENVIADO') THEN estado
                WHEN estado = 'ENVIADO' AND @est = 'PENDIENTE' THEN estado
                ELSE @est END WHERE id_mensaje = @id";
            cmd.Parameters.AddWithValue("@est", estado);
            cmd.Parameters.AddWithValue("@id", idMensaje);
            cmd.ExecuteNonQuery();
        }

        public List<string> IdsNoLeidos(string yo, string otro)
        {
            var ids = new List<string>();
            using var cmd = _store.Connection.CreateCommand();
            cmd.CommandText = @"SELECT id_mensaje FROM historial_local
                WHERE origen = @otro AND destino = @yo AND estado <> 'LEIDO'
                AND tipo IN ('MENSAJE_TEXTO', 'MENSAJE_IMAGEN', 'MENSAJE_ARCHIVO', 'SYNC_LOGIN')
                ORDER BY fecha_envio ASC";
            cmd.Parameters.AddWithValue("@yo", yo);
            cmd.Parameters.AddWithValue("@otro", otro);
            using var reader = cmd.ExecuteReader();
            while (reader.Read()) ids.Add(reader.GetString(0));
            return ids;
        }

        public void MarcarLeidos(string yo, string otro)
        {
            using var cmd = _store.Connection.CreateCommand();
            cmd.CommandText = @"UPDATE historial_local SET estado = 'LEIDO'
                WHERE origen = @otro AND destino = @yo
                AND tipo IN ('MENSAJE_TEXTO', 'MENSAJE_IMAGEN', 'MENSAJE_ARCHIVO', 'SYNC_LOGIN')";
            cmd.Parameters.AddWithValue("@yo", yo);
            cmd.Parameters.AddWithValue("@otro", otro);
            cmd.ExecuteNonQuery();
        }

        public List<Dictionary<string, object?>> ListarPendientes()
        {
            var result = new List<Dictionary<string, object?>>();
            using var cmd = _store.Connection.CreateCommand();
            cmd.CommandText = @"
SELECT id, tipo, origen, destino, contenido, nombre_archivo, payload,
       fecha_creado, intentos, ultimo_error
FROM pendientes_envio
ORDER BY fecha_creado ASC";
            using var reader = cmd.ExecuteReader();
            while (reader.Read())
            {
                result.Add(new Dictionary<string, object?>
                {
                    { "id", reader.GetString(0) },
                    { "tipo", reader.GetString(1) },
                    { "origen", reader.GetString(2) },
                    { "destino", reader.GetString(3) },
                    { "contenido", reader.IsDBNull(4) ? null : reader.GetString(4) },
                    { "nombreArchivo", reader.IsDBNull(5) ? null : reader.GetString(5) },
                    { "payload", reader.IsDBNull(6) ? null : (byte[])reader.GetValue(6) },
                    { "fechaCreado", reader.GetString(7) },
                    { "intentos", reader.GetInt64(8) },
                    { "ultimoError", reader.IsDBNull(9) ? null : reader.GetString(9) }
                });
            }
            return result;
        }

        public void RegistrarPendiente(string id, string tipo, string origen, string destino,
            string? contenido, string? nombreArchivo, byte[]? payload, string fechaCreado)
        {
            using var tx = _store.Connection.BeginTransaction();
            try
            {
                using (var cmdDel = _store.Connection.CreateCommand())
                {
                    cmdDel.Transaction = tx;
                    cmdDel.CommandText = "DELETE FROM pendientes_envio WHERE id = @id";
                    cmdDel.Parameters.AddWithValue("@id", id);
                    cmdDel.ExecuteNonQuery();
                }
                using (var cmdIns = _store.Connection.CreateCommand())
                {
                    cmdIns.Transaction = tx;
                    cmdIns.CommandText = @"
INSERT INTO pendientes_envio (id, tipo, origen, destino, contenido,
    nombre_archivo, payload, fecha_creado, intentos, ultimo_error)
VALUES (@id, @tip, @ori, @des, @con, @nar, @pay, @fec, 0, NULL)";
                    cmdIns.Parameters.AddWithValue("@id", id);
                    cmdIns.Parameters.AddWithValue("@tip", tipo);
                    cmdIns.Parameters.AddWithValue("@ori", origen);
                    cmdIns.Parameters.AddWithValue("@des", destino);
                    cmdIns.Parameters.AddWithValue("@con", (object?)contenido ?? DBNull.Value);
                    cmdIns.Parameters.AddWithValue("@nar", (object?)nombreArchivo ?? DBNull.Value);
                    cmdIns.Parameters.AddWithValue("@pay", (object?)payload ?? DBNull.Value);
                    cmdIns.Parameters.AddWithValue("@fec", fechaCreado);
                    cmdIns.ExecuteNonQuery();
                }
                tx.Commit();
            }
            catch
            {
                tx.Rollback();
                throw;
            }
        }

        public void IncrementarIntento(string id, string ultimoError)
        {
            using var cmd = _store.Connection.CreateCommand();
            cmd.CommandText = @"
UPDATE pendientes_envio
SET intentos = intentos + 1, ultimo_error = @err
WHERE id = @id";
            cmd.Parameters.AddWithValue("@err", ultimoError);
            cmd.Parameters.AddWithValue("@id", id);
            cmd.ExecuteNonQuery();
        }

        public void EliminarPendiente(string id)
        {
            using var cmd = _store.Connection.CreateCommand();
            cmd.CommandText = "DELETE FROM pendientes_envio WHERE id = @id";
            cmd.Parameters.AddWithValue("@id", id);
            cmd.ExecuteNonQuery();
        }
    }
}
