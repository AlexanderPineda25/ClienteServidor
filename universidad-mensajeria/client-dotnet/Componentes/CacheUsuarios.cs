using System;
using System.Collections.Generic;
using Microsoft.Data.Sqlite;
using Mensajeria.Store;

namespace Mensajeria.Componentes
{
    /// <summary>DAO SQLite de cache_usuarios (HISTORIAL_LOCAL.md §6-7).</summary>
    public class CacheUsuarios : ICacheUsuarios
    {
        private readonly LocalStore _store;

        public CacheUsuarios(LocalStore store)
        {
            _store = store;
        }

        public void Guardar(string codigo, string nombres, string apellidos,
            string? programa, int conectado, string? fechaRegistro, string actualizado)
        {
            using var tx = _store.Connection.BeginTransaction();
            try
            {
                using (var cmdDel = _store.Connection.CreateCommand())
                {
                    cmdDel.Transaction = tx;
                    cmdDel.CommandText = "DELETE FROM cache_usuarios WHERE codigo = @cod";
                    cmdDel.Parameters.AddWithValue("@cod", codigo);
                    cmdDel.ExecuteNonQuery();
                }
                using (var cmdIns = _store.Connection.CreateCommand())
                {
                    cmdIns.Transaction = tx;
                    cmdIns.CommandText = @"
INSERT INTO cache_usuarios (codigo, nombres, apellidos, programa,
    conectado, fecha_registro, actualizado)
VALUES (@cod, @nom, @ape, @pro, @con, @fre, @act)";
                    cmdIns.Parameters.AddWithValue("@cod", codigo);
                    cmdIns.Parameters.AddWithValue("@nom", nombres);
                    cmdIns.Parameters.AddWithValue("@ape", apellidos);
                    cmdIns.Parameters.AddWithValue("@pro", (object?)programa ?? DBNull.Value);
                    cmdIns.Parameters.AddWithValue("@con", conectado);
                    cmdIns.Parameters.AddWithValue("@fre", (object?)fechaRegistro ?? DBNull.Value);
                    cmdIns.Parameters.AddWithValue("@act", actualizado);
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

        public List<Dictionary<string, object>> Listar()
        {
            var result = new List<Dictionary<string, object>>();
            using var cmd = _store.Connection.CreateCommand();
            cmd.CommandText = @"
SELECT codigo, nombres, apellidos, programa, conectado, fecha_registro, actualizado
FROM cache_usuarios
ORDER BY conectado DESC, apellidos ASC";
            using var reader = cmd.ExecuteReader();
            while (reader.Read())
            {
                var row = new Dictionary<string, object>
                {
                    { "codigo", reader.GetString(0) },
                    { "nombres", reader.GetString(1) },
                    { "apellidos", reader.GetString(2) },
                    { "conectado", reader.GetInt64(4) },
                    { "actualizado", reader.GetString(6) }
                };
                if (!reader.IsDBNull(3)) row["programa"] = reader.GetString(3);
                if (!reader.IsDBNull(5)) row["fechaRegistro"] = reader.GetString(5);
                result.Add(row);
            }
            return result;
        }

        public Dictionary<string, object>? PorCodigo(string codigo)
        {
            using var cmd = _store.Connection.CreateCommand();
            cmd.CommandText = @"
SELECT codigo, nombres, apellidos, programa, conectado, fecha_registro, actualizado
FROM cache_usuarios
WHERE codigo = @cod";
            cmd.Parameters.AddWithValue("@cod", codigo);
            using var reader = cmd.ExecuteReader();
            if (!reader.Read()) return null;
            var row = new Dictionary<string, object>
            {
                { "codigo", reader.GetString(0) },
                { "nombres", reader.GetString(1) },
                { "apellidos", reader.GetString(2) },
                { "conectado", reader.GetInt64(4) },
                { "actualizado", reader.GetString(6) }
            };
            if (!reader.IsDBNull(3)) row["programa"] = reader.GetString(3);
            if (!reader.IsDBNull(5)) row["fechaRegistro"] = reader.GetString(5);
            return row;
        }

        public void MarcarConectados(List<string> codigos)
        {
            using var tx = _store.Connection.BeginTransaction();
            try
            {
                using (var cmdTodos = _store.Connection.CreateCommand())
                {
                    cmdTodos.Transaction = tx;
                    cmdTodos.CommandText = "UPDATE cache_usuarios SET conectado = 0";
                    cmdTodos.ExecuteNonQuery();
                }
                foreach (var codigo in codigos)
                {
                    using var cmdUno = _store.Connection.CreateCommand();
                    cmdUno.Transaction = tx;
                    cmdUno.CommandText = "UPDATE cache_usuarios SET conectado = 1 WHERE codigo = @cod";
                    cmdUno.Parameters.AddWithValue("@cod", codigo);
                    cmdUno.ExecuteNonQuery();
                }
                tx.Commit();
            }
            catch
            {
                tx.Rollback();
                throw;
            }
        }
    }
}
