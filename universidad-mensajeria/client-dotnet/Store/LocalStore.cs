using System;
using System.Collections.Generic;
using System.IO;
using Microsoft.Data.Sqlite;

namespace Mensajeria.Store
{
    public class LocalStore : IDisposable
    {
        private readonly SqliteConnection _conn;
        public string DbPath { get; }

        public LocalStore(string dbPath)
        {
            string expandedPath = ExpandirRuta(dbPath);
            DbPath = expandedPath;

            string? dir = Path.GetDirectoryName(expandedPath);
            if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);

            // Concurrencia multi-proceso (dos sesiones misma cuenta): WAL + espera acotada.
            _conn = new SqliteConnection($"Data Source={expandedPath};Cache=Shared;Mode=ReadWriteCreate;Default Timeout=30");
            _conn.Open();
            AplicarConcurrencia();
            Inicializar();
        }

        public SqliteConnection Connection => _conn;

        // ---------- Resolución por cuenta (convención compartida Python/.NET) ----------
        // Base "<base>.db" + cuenta "A001" => "<base>_A001.db". Misma ruta para los
        // dos clientes dada la misma db.url base y la misma cuenta.
        public static string ExpandirRuta(string dbPath)
        {
            if (dbPath.StartsWith("sqlite:///")) dbPath = dbPath["sqlite:///".Length..];
            return dbPath.Replace("~", Environment.GetFolderPath(Environment.SpecialFolder.UserProfile));
        }

        public static string SanitizarCuenta(string cuenta)
        {
            if (string.IsNullOrWhiteSpace(cuenta)) throw new ArgumentException("cuenta vacía");
            var sb = new System.Text.StringBuilder();
            foreach (char c in cuenta.Trim())
                sb.Append(char.IsLetterOrDigit(c) || c == '-' || c == '_' ? c : '_');
            string s = sb.ToString().Trim('_');
            if (s.Length == 0) throw new ArgumentException("cuenta sin caracteres válidos");
            return s;
        }

        public static string RutaParaCuenta(string dbUrlOCamino, string cuenta)
        {
            string base_ = ExpandirRuta(dbUrlOCamino);
            string limpia = SanitizarCuenta(cuenta);
            string dir = Path.GetDirectoryName(base_) ?? "";
            string nombre = Path.GetFileName(base_);
            string stem = Path.GetFileNameWithoutExtension(nombre);
            string ext = Path.GetExtension(nombre);
            if (string.IsNullOrEmpty(ext)) ext = ".db";
            // Evita doble sufijo si ya termina en _<cuenta>.
            if (stem.EndsWith("_" + limpia, StringComparison.OrdinalIgnoreCase))
                return Path.Combine(dir, stem + ext);
            return Path.Combine(dir, stem + "_" + limpia + ext);
        }

        public static string RutaLegada(string dbUrlOCamino) => ExpandirRuta(dbUrlOCamino);

        /// <summary>
        /// Migración idempotente legado -&gt; archivo por cuenta. Copia mensajes a la
        /// cuenta, recalcula enviado según la cuenta (origen==cuenta?1:0), reparte
        /// pendientes según su origen y copia caché sin duplicar. Mantiene el
        /// archivo legado intacto como respaldo. Nunca hace retroceder estado.
        /// </summary>
        public static void MigrarLegadoACuenta(string rutaLegada, string rutaCuenta, string cuenta)
        {
            string leg = ExpandirRuta(rutaLegada);
            string cta = ExpandirRuta(rutaCuenta);
            if (string.Equals(leg, cta, StringComparison.OrdinalIgnoreCase)) return;
            if (!File.Exists(leg)) return;
            string? dir = Path.GetDirectoryName(cta);
            if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
            bool cuentaExistia = File.Exists(cta);

            using var connLeg = new SqliteConnection($"Data Source={leg};Mode=ReadOnly;Default Timeout=30");
            connLeg.Open();
            if (!TablaExiste(connLeg, "historial_local")) return;

            using var connCta = new SqliteConnection($"Data Source={cta};Cache=Shared;Mode=ReadWriteCreate;Default Timeout=30");
            connCta.Open();
            using (var cmd = connCta.CreateCommand())
            {
                cmd.CommandText = "PRAGMA journal_mode=WAL;"; cmd.ExecuteNonQuery();
            }
            using (var cmd = connCta.CreateCommand())
            {
                cmd.CommandText = "PRAGMA busy_timeout=5000;"; cmd.ExecuteNonQuery();
            }
            // Asegura esquema en destino (usa LocalStore para el fallback si falta el .sql).
            connCta.Close();
            try { using var init = new LocalStore(cta) { }; } catch { }
            connCta.Open();
            using (var cmd = connCta.CreateCommand())
            {
                cmd.CommandText = "PRAGMA journal_mode=WAL;"; try { cmd.ExecuteNonQuery(); } catch { }
            }
            using (var cmd = connCta.CreateCommand())
            {
                cmd.CommandText = "PRAGMA busy_timeout=5000;"; try { cmd.ExecuteNonQuery(); } catch { }
            }
            AsegurarColumnas(connCta);

            // Lee legado.
            var filas = new List<Dictionary<string, object?>>();
            using (var cmd = connLeg.CreateCommand())
            {
                cmd.CommandText = @"SELECT id_mensaje, origen, destino, tipo, contenido, hash_sha256,
                    num_caracteres, num_palabras, nombre_archivo, archivo_id, ruta_archivo,
                    tamano_archivo, fecha_envio, enviado, descargado, estado
                    FROM historial_local";
                try
                {
                    using var r = cmd.ExecuteReader();
                    while (r.Read())
                    {
                        var d = new Dictionary<string, object?>();
                        for (int i = 0; i < r.FieldCount; i++)
                            d[r.GetName(i)] = r.IsDBNull(i) ? null : r.GetValue(i);
                        filas.Add(d);
                    }
                }
                catch { return; }
            }
            // Inserta idempotente con enviado recalculado y estado monotónico.
            foreach (var f in filas)
            {
                string? id = f["id_mensaje"]?.ToString(); if (id == null) continue;
                string origen = f["origen"]?.ToString() ?? "";
                int enviado = origen == cuenta ? 1 : 0;
                using var cmd = connCta.CreateCommand();
                cmd.CommandText = @"
INSERT INTO historial_local (id_mensaje, origen, destino, tipo, contenido, hash_sha256,
    num_caracteres, num_palabras, nombre_archivo, ruta_archivo, tamano_archivo,
    fecha_envio, enviado, descargado, estado, archivo_id)
VALUES (@id,@ori,@des,@tip,@con,@has,@nca,@npa,@nar,@rar,@tar,@fen,@env,@desca,@est,@aid)
ON CONFLICT(id_mensaje) DO UPDATE SET
    descargado = MAX(excluded.descargado, historial_local.descargado),
    archivo_id = COALESCE(excluded.archivo_id, historial_local.archivo_id),
    ruta_archivo = COALESCE(excluded.ruta_archivo, historial_local.ruta_archivo),
    estado = CASE
        WHEN historial_local.estado = 'LEIDO' OR excluded.estado = 'LEIDO' THEN 'LEIDO'
        WHEN historial_local.estado = 'ENTREGADO' AND excluded.estado IN ('PENDIENTE','ENVIADO') THEN 'ENTREGADO'
        WHEN historial_local.estado = 'ENVIADO' AND excluded.estado = 'PENDIENTE' THEN 'ENVIADO'
        ELSE excluded.estado END";
                cmd.Parameters.AddWithValue("@id", id);
                cmd.Parameters.AddWithValue("@ori", origen);
                cmd.Parameters.AddWithValue("@des", f["destino"]?.ToString() ?? "");
                cmd.Parameters.AddWithValue("@tip", f["tipo"]?.ToString() ?? "MENSAJE_TEXTO");
                cmd.Parameters.AddWithValue("@con", f["contenido"] ?? DBNull.Value);
                cmd.Parameters.AddWithValue("@has", f["hash_sha256"] ?? DBNull.Value);
                cmd.Parameters.AddWithValue("@nca", f["num_caracteres"] ?? DBNull.Value);
                cmd.Parameters.AddWithValue("@npa", f["num_palabras"] ?? DBNull.Value);
                cmd.Parameters.AddWithValue("@nar", f["nombre_archivo"] ?? DBNull.Value);
                cmd.Parameters.AddWithValue("@rar", f["ruta_archivo"] ?? DBNull.Value);
                cmd.Parameters.AddWithValue("@tar", f["tamano_archivo"] ?? DBNull.Value);
                cmd.Parameters.AddWithValue("@fen", f["fecha_envio"]?.ToString() ?? DateTime.UtcNow.ToString("O"));
                cmd.Parameters.AddWithValue("@env", enviado);
                cmd.Parameters.AddWithValue("@desca", f["descargado"] ?? 0);
                cmd.Parameters.AddWithValue("@est", f["estado"]?.ToString() ?? "ENVIADO");
                cmd.Parameters.AddWithValue("@aid", f["archivo_id"] ?? DBNull.Value);
                try { cmd.ExecuteNonQuery(); } catch { }
            }
            // Pendientes: solo los originados por esta cuenta.
            try
            {
                if (TablaExiste(connLeg, "pendientes_envio"))
                {
                    using var cmd = connLeg.CreateCommand();
                    cmd.CommandText = @"SELECT id, tipo, origen, destino, contenido, nombre_archivo,
                        payload, fecha_creado, intentos, ultimo_error FROM pendientes_envio WHERE origen = @o";
                    cmd.Parameters.AddWithValue("@o", cuenta);
                    using var r = cmd.ExecuteReader();
                    var pends = new List<Dictionary<string, object?>>();
                    while (r.Read())
                    {
                        var d = new Dictionary<string, object?>();
                        for (int i = 0; i < r.FieldCount; i++)
                            d[r.GetName(i)] = r.IsDBNull(i) ? null : r.GetValue(i);
                        pends.Add(d);
                    }
                    r.Close();
                    foreach (var p in pends)
                    {
                        using var ins = connCta.CreateCommand();
                        ins.CommandText = @"INSERT OR IGNORE INTO pendientes_envio
                            (id, tipo, origen, destino, contenido, nombre_archivo, payload, fecha_creado, intentos, ultimo_error)
                            VALUES (@id,@tip,@ori,@des,@con,@nar,@pay,@fec,@int,@err)";
                        ins.Parameters.AddWithValue("@id", p["id"] ?? DBNull.Value);
                        ins.Parameters.AddWithValue("@tip", p["tipo"] ?? DBNull.Value);
                        ins.Parameters.AddWithValue("@ori", p["origen"] ?? DBNull.Value);
                        ins.Parameters.AddWithValue("@des", p["destino"] ?? DBNull.Value);
                        ins.Parameters.AddWithValue("@con", p["contenido"] ?? DBNull.Value);
                        ins.Parameters.AddWithValue("@nar", p["nombre_archivo"] ?? DBNull.Value);
                        ins.Parameters.AddWithValue("@pay", p["payload"] ?? DBNull.Value);
                        ins.Parameters.AddWithValue("@fec", p["fecha_creado"] ?? DBNull.Value);
                        ins.Parameters.AddWithValue("@int", p["intentos"] ?? 0);
                        ins.Parameters.AddWithValue("@err", p["ultimo_error"] ?? DBNull.Value);
                        try { ins.ExecuteNonQuery(); } catch { }
                    }
                }
            }
            catch { }
            // Caché: copia sin duplicar.
            try
            {
                if (TablaExiste(connLeg, "cache_usuarios"))
                {
                    using var cmd = connLeg.CreateCommand();
                    cmd.CommandText = @"SELECT codigo, nombres, apellidos, programa, conectado, fecha_registro, actualizado FROM cache_usuarios";
                    using var r = cmd.ExecuteReader();
                    var users = new List<Dictionary<string, object?>>();
                    while (r.Read())
                    {
                        var d = new Dictionary<string, object?>();
                        for (int i = 0; i < r.FieldCount; i++)
                            d[r.GetName(i)] = r.IsDBNull(i) ? null : r.GetValue(i);
                        users.Add(d);
                    }
                    r.Close();
                    foreach (var u in users)
                    {
                        using var ins = connCta.CreateCommand();
                        ins.CommandText = @"INSERT OR IGNORE INTO cache_usuarios
                            (codigo, nombres, apellidos, programa, conectado, fecha_registro, actualizado)
                            VALUES (@c,@n,@a,@p,@con,@fr,@act)";
                        ins.Parameters.AddWithValue("@c", u["codigo"] ?? DBNull.Value);
                        ins.Parameters.AddWithValue("@n", u["nombres"] ?? DBNull.Value);
                        ins.Parameters.AddWithValue("@a", u["apellidos"] ?? DBNull.Value);
                        ins.Parameters.AddWithValue("@p", u["programa"] ?? DBNull.Value);
                        ins.Parameters.AddWithValue("@con", u["conectado"] ?? 0);
                        ins.Parameters.AddWithValue("@fr", u["fecha_registro"] ?? DBNull.Value);
                        ins.Parameters.AddWithValue("@act", u["actualizado"] ?? DateTime.UtcNow.ToString("O"));
                        try { ins.ExecuteNonQuery(); } catch { }
                    }
                }
            }
            catch { }
            _ = cuentaExistia;
        }

        private static bool TablaExiste(SqliteConnection conn, string tabla)
        {
            using var cmd = conn.CreateCommand();
            cmd.CommandText = "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=@t";
            cmd.Parameters.AddWithValue("@t", tabla);
            try { return Convert.ToInt32(cmd.ExecuteScalar()) > 0; } catch { return false; }
        }

        private static void AsegurarColumnas(SqliteConnection conn)
        {
            var cols = new HashSet<string>();
            using (var cmd = conn.CreateCommand())
            {
                cmd.CommandText = "PRAGMA table_info(historial_local)";
                using var r = cmd.ExecuteReader();
                while (r.Read()) cols.Add(r.GetString(1));
            }
            if (!cols.Contains("estado"))
            {
                using var cmd = conn.CreateCommand();
                cmd.CommandText = "ALTER TABLE historial_local ADD COLUMN estado TEXT NOT NULL DEFAULT 'ENVIADO'";
                try { cmd.ExecuteNonQuery(); } catch { }
            }
            if (!cols.Contains("archivo_id"))
            {
                using var cmd = conn.CreateCommand();
                cmd.CommandText = "ALTER TABLE historial_local ADD COLUMN archivo_id TEXT";
                try { cmd.ExecuteNonQuery(); } catch { }
            }
        }

        private void AplicarConcurrencia()
        {
            foreach (string pragma in new[] { "PRAGMA journal_mode=WAL;", "PRAGMA busy_timeout=5000;", "PRAGMA synchronous=NORMAL;" })
            {
                try
                {
                    using var cmd = _conn.CreateCommand();
                    cmd.CommandText = pragma; cmd.ExecuteNonQuery();
                }
                catch { }
            }
        }

        private void Inicializar()
        {
            string schemaPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "Store", "schema-local.sql");
            if (File.Exists(schemaPath))
            {
                string schema = File.ReadAllText(schemaPath);
                using var cmd = _conn.CreateCommand();
                cmd.CommandText = schema;
                cmd.ExecuteNonQuery();
            }
            else
            {
                // Fallback si no está el archivo (para cumplir con la instrucción de schema-local.sql)
                string fallbackSchema = @"
CREATE TABLE IF NOT EXISTS historial_local (
    id_mensaje     TEXT PRIMARY KEY,
    origen         TEXT NOT NULL,
    destino        TEXT NOT NULL,
    tipo           TEXT NOT NULL,
    contenido      TEXT,
    hash_sha256    TEXT,
    num_caracteres INTEGER,
    num_palabras   INTEGER,
    nombre_archivo TEXT,
    archivo_id     TEXT,
    ruta_archivo   TEXT,
    tamano_archivo INTEGER,
    fecha_envio    TEXT NOT NULL,
    enviado        INTEGER NOT NULL DEFAULT 0,
    descargado     INTEGER NOT NULL DEFAULT 0,
    estado         TEXT NOT NULL DEFAULT 'ENVIADO'
);
CREATE INDEX IF NOT EXISTS idx_hist_conv ON historial_local (origen, destino, fecha_envio);
CREATE TABLE IF NOT EXISTS cache_usuarios (
    codigo         TEXT PRIMARY KEY,
    nombres        TEXT NOT NULL,
    apellidos      TEXT NOT NULL,
    programa       TEXT,
    conectado      INTEGER NOT NULL DEFAULT 0,
    fecha_registro TEXT,
    actualizado    TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS pendientes_envio (
    id             TEXT PRIMARY KEY,
    tipo           TEXT NOT NULL,
    origen         TEXT NOT NULL,
    destino        TEXT NOT NULL,
    contenido      TEXT,
    nombre_archivo TEXT,
    payload        BLOB,
    fecha_creado   TEXT NOT NULL,
    intentos       INTEGER NOT NULL DEFAULT 0,
    ultimo_error   TEXT
);
CREATE INDEX IF NOT EXISTS idx_pend_destino ON pendientes_envio (destino, fecha_creado);
";
                using var cmd = _conn.CreateCommand();
                cmd.CommandText = fallbackSchema;
                cmd.ExecuteNonQuery();
            }
            MigrarHistorial();
        }

        private void MigrarHistorial()
        {
            using var tx = _conn.BeginTransaction();
            var columnas = new HashSet<string>();
            using (var cmd = _conn.CreateCommand())
            {
                cmd.Transaction = tx;
                cmd.CommandText = "PRAGMA table_info(historial_local)";
                using var reader = cmd.ExecuteReader();
                while (reader.Read()) columnas.Add(reader.GetString(1));
            }
            if (!columnas.Contains("estado"))
            {
                using var cmd = _conn.CreateCommand();
                cmd.Transaction = tx;
                cmd.CommandText = "ALTER TABLE historial_local ADD COLUMN estado TEXT NOT NULL DEFAULT 'ENVIADO'";
                cmd.ExecuteNonQuery();
            }
            if (!columnas.Contains("archivo_id"))
            {
                using var cmd = _conn.CreateCommand();
                cmd.Transaction = tx;
                cmd.CommandText = "ALTER TABLE historial_local ADD COLUMN archivo_id TEXT";
                cmd.ExecuteNonQuery();
            }
            tx.Commit();
        }

        public void Dispose()
        {
            _conn.Close();
            _conn.Dispose();
        }
    }
}
