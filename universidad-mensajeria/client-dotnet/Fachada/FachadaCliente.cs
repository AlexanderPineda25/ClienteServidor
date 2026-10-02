using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Sockets;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Mensajeria.Componentes;
using Mensajeria.Store;
using Mensajeria.Transversal;

namespace Mensajeria.Fachada
{
    /// <summary>
    /// Unico punto de entrada de la UI (espejo de FachadaCliente Java, Fase 5):
    /// inyecta interfaces, nunca implementaciones. Enviado con red → pedir() +
    /// ACK con metadatos; sin red → historial + pendiente con reintento.
    /// </summary>
    public class FachadaCliente : IFachadaCliente
    {
        public const string DestinoBroadcast = "*";

        private IConexionRed? _conexion;
        private IAutenticacion? _autenticacion;
        private readonly IHistorialLocal? _historial;
        private readonly ICacheUsuarios? _cache;

        private string _host = "localhost";
        private int _puerto = 5000;
        private bool _tlsHabilitado;
        private int _tlsPuerto = 5001;
        private string _dbUrl = "sqlite:///~/mensajeria_cliente.db";
        private LocalStore? _store;
        private readonly bool _dependenciasInyectadas;
        public string? RutaDbActual => _store?.DbPath;

        public event Action<Dictionary<string, JsonElement>>? OnMensajeRecibido;
        public event Action<Dictionary<string, JsonElement>>? OnCierre;
        public event Action<Dictionary<string, JsonElement>>? OnPresencia;
        public event Action? OnDesconectado;
        public string? UsuarioActual => _autenticacion?.CodigoActual;

        /// <summary>Produccion: lee client.properties (mismo formato, §5.2).</summary>
        public FachadaCliente()
        {
            _dependenciasInyectadas = false;
            CargarPropiedades();
        }

        /// <summary>Tests / DI: componentes ya montados.</summary>
        public FachadaCliente(IConexionRed conexion, IAutenticacion autenticacion,
            IHistorialLocal? historial, ICacheUsuarios? cache)
        {
            _dependenciasInyectadas = true;
            _conexion = conexion;
            _autenticacion = autenticacion;
            _historial = historial;
            _cache = cache;
            SuscribirRed();
        }

        private void CargarPropiedades()
        {
            try
            {
                var path = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "client.properties");
                if (!File.Exists(path)) return;
                foreach (var linea in File.ReadAllLines(path))
                {
                    var t = linea.Trim();
                    if (t.Length == 0 || t.StartsWith("#")) continue;
                    int eq = t.IndexOf('=');
                    if (eq < 0) continue;
                    string clave = t[..eq].Trim(), valor = t[(eq + 1)..].Trim();
                    if (clave == "host" && valor.Length > 0) _host = valor;
                    else if (clave == "puerto" && int.TryParse(valor, out int p) && p > 0 && p <= 65535) _puerto = p;
                    else if (clave == "tls.habilitado") _tlsHabilitado = valor.Equals("true", StringComparison.OrdinalIgnoreCase);
                    else if (clave == "tls.puerto" && int.TryParse(valor, out int tp) && tp > 0 && tp <= 65535) _tlsPuerto = tp;
                    else if (clave == "db.url" && valor.Length > 0) _dbUrl = valor;
                }
            }
            catch { }
        }

        public Task ConectarAsync() => ConectarAsync(_host, _puerto);

        public async Task ConectarAsync(string host, int puerto)
        {
            if (_dependenciasInyectadas)
            {
                await _conexion!.ConectarAsync(host, puerto);
                return;
            }
            var conexion = new ConexionRed
            {
                TlsHabilitado = _tlsHabilitado,
                TlsPuerto = _tlsPuerto
            };
            await conexion.ConectarAsync(host, puerto);
            _conexion = conexion;
            string dbPath = _dbUrl.StartsWith("sqlite:///") ? _dbUrl["sqlite:///".Length..] : _dbUrl;
            _store = new LocalStore(dbPath);
            // Nota: _historial/_cache readonly solo en ctor DI; aqui se asignan una vez.
            _historialProd = new HistorialLocal(_store);
            _cacheProd = new CacheUsuarios(_store);
            _autenticacion = new Autenticacion(conexion);
            SuscribirRed();
        }

        // Backing para la ruta de produccion (la de tests viene por ctor).
        private HistorialLocal? _historialProd;
        private CacheUsuarios? _cacheProd;
        private IHistorialLocal? Historial => _historial ?? _historialProd;
        private ICacheUsuarios? Cache => _cache ?? _cacheProd;

        private void SuscribirRed()
        {
            if (_conexion == null) return;
            _conexion.OnMensajeRecibido += AlRecibir;
            _conexion.OnCierre += msg => OnCierre?.Invoke(msg);
            _conexion.OnDesconectado += () => OnDesconectado?.Invoke();
        }

        public void Desconectar()
        {
            DetenerFlushPendientes();
            _autenticacion?.Logout();
            _conexion?.Desconectar();
            _store?.Dispose();
            _store = null;
        }

        public async Task<bool> LoginAsync(string codigo, string contrasena)
        {
            if (_autenticacion == null) throw new InvalidOperationException("ConectarAsync primero");
            bool ok = await _autenticacion.LoginAsync(codigo, contrasena);
            if (ok)
            {
                CambiarAStorePorCuenta(codigo);
                AsegurarEnCache(codigo);
                await ReintentarPendientesAsync();
                IniciarFlushPendientes(IntervaloFlushSegundos);
            }
            return ok;
        }

        /// <summary>
        /// FASE 11.1: worker que vacia pendientes en orden cada cierto tiempo
        /// (best-effort). Cubre la reconexion silenciosa sin accion de la UI.
        /// </summary>
        public const int IntervaloFlushSegundos = 30;
        private System.Threading.Timer? _flush;
        private readonly object _flushLock = new();

        public void IniciarFlushPendientes(int segundos)
        {
            lock (_flushLock)
            {
                if (_flush != null) return;
                var periodo = TimeSpan.FromSeconds(Math.Max(1, segundos));
                _flush = new System.Threading.Timer(_ =>
                {
                    try { ReintentarPendientesAsync().GetAwaiter().GetResult(); }
                    catch { }
                }, null, periodo, periodo);
            }
        }

        public void DetenerFlushPendientes()
        {
            lock (_flushLock)
            {
                _flush?.Dispose();
                _flush = null;
            }
        }

        /// <summary>
        /// Resuelve SQLite por cuenta (&lt;base&gt;_&lt;cuenta&gt;.db, misma ruta que Python),
        /// migra el legado de forma idempotente y conmuta el historial/caché.
        /// Dos procesos con la misma cuenta comparten archivo (WAL); cuentas
        /// distintas nunca comparten filas. El legado queda como respaldo.
        /// </summary>
        private void CambiarAStorePorCuenta(string codigo)
        {
            if (_dependenciasInyectadas) return;
            try
            {
                string legada = LocalStore.RutaLegada(_dbUrl);
                string porCuenta = LocalStore.RutaParaCuenta(_dbUrl, codigo);
                if (_store != null && string.Equals(_store.DbPath, porCuenta, StringComparison.OrdinalIgnoreCase))
                    return;
                LocalStore.MigrarLegadoACuenta(legada, porCuenta, codigo);
                _store?.Dispose();
                _store = new LocalStore(porCuenta);
                _historialProd = new HistorialLocal(_store);
                _cacheProd = new CacheUsuarios(_store);
            }
            catch { }
        }

        /// <summary>Conectados del servidor (array de strings, PROTOCOLO.md §3) + cache.</summary>
        public async Task<List<string>> ListarConectadosAsync()
        {
            if (_conexion == null) throw new InvalidOperationException("ConectarAsync primero");
            var msg = new Dictionary<string, object?> { { "tipo", TipoMensaje.LISTAR_CONECTADOS } };
            var resp = await _conexion.Pedir(msg).ConfigureAwait(false);

            var lista = new List<string>();
            if (resp.TryGetValue("usuariosConectados", out var arr) && arr.ValueKind == JsonValueKind.Array)
            {
                foreach (var item in arr.EnumerateArray())
                {
                    if (item.ValueKind == JsonValueKind.String)
                        lista.Add(item.GetString()!);
                    else if (item.ValueKind == JsonValueKind.Object
                        && item.TryGetProperty("codigo", out var c)
                        && c.ValueKind == JsonValueKind.String)
                        lista.Add(c.GetString()!); // tolerante, el contrato manda strings
                }
            }
            Cache?.MarcarConectados(lista);
            foreach (var codigo in lista) AsegurarEnCache(codigo);
            return lista;
        }

        public async Task<List<Dictionary<string, object>>> ListarUsuariosAsync()
        {
            if (_conexion == null) throw new InvalidOperationException("ConectarAsync primero");
            var resp = await _conexion.Pedir(new Dictionary<string, object?>
                { { "tipo", TipoMensaje.LISTAR_USUARIOS } }).ConfigureAwait(false);
            // El servidor envia el directorio en `contenido` (JSON) — ListarCaso.java,
            // igual que lee el cliente Java (FachadaCliente.sincronizarDirectorio).
            string contenido = GetString(resp, "contenido") ?? "[]";
            JsonDocument documento;
            try { documento = JsonDocument.Parse(contenido); }
            catch { return DirectorioLocal(); }
            using (documento)
            {
                if (documento.RootElement.ValueKind != JsonValueKind.Array)
                    return DirectorioLocal();
                var usuarios = new List<Dictionary<string, object>>();
                foreach (var u in documento.RootElement.EnumerateArray())
                {
                    if (u.ValueKind != JsonValueKind.Object) continue;
                    string codigo = Texto(u, "codigo") ?? "";
                    if (codigo.Length == 0) continue;
                    string nombres = Texto(u, "nombres") ?? "";
                    string apellidos = Texto(u, "apellidos") ?? "";
                    string? programa = Texto(u, "programa");
                    bool conectado = u.TryGetProperty("conectado", out var c)
                        && c.ValueKind == JsonValueKind.True;
                    Cache?.Guardar(codigo, nombres, apellidos, programa,
                        conectado ? 1 : 0, Texto(u, "fechaRegistro"), DateTime.UtcNow.ToString("O"));
                    usuarios.Add(new Dictionary<string, object>
                    {
                        ["codigo"] = codigo, ["nombres"] = nombres,
                        ["apellidos"] = apellidos, ["conectado"] = conectado
                    });
                }
                if (usuarios.Count == 0) return DirectorioLocal();
                Cache?.MarcarConectados(usuarios.Where(u => (bool)u["conectado"])
                    .Select(u => (string)u["codigo"]).ToList());
                return usuarios;
            }
        }

        public async Task MarcarLeidosAsync(string otroUsuario)
        {
            if (UsuarioActual == null || _conexion == null)
                throw new InvalidOperationException("inicia sesion primero");
            var pendientes = Pendientes();
            foreach (string id in Historial?.IdsNoLeidos(UsuarioActual, otroUsuario) ?? new List<string>())
            {
                string idPendiente = "lectura:" + id;
                if (pendientes.Any(p => (string?)p["id"] == idPendiente)) continue;
                Historial?.RegistrarPendiente(idPendiente, TipoMensaje.MENSAJE_LEIDO,
                    UsuarioActual, otroUsuario, id, null, null, DateTime.UtcNow.ToString("O"));
            }
            Historial?.MarcarLeidos(UsuarioActual, otroUsuario);
            await ReintentarPendientesAsync().ConfigureAwait(false);
        }

        public async Task ExpulsarOtrasSesionesAsync()
        {
            if (_conexion == null || UsuarioActual == null)
                throw new InvalidOperationException("inicia sesion primero");
            var resp = await _conexion.Pedir(new Dictionary<string, object?>
            {
                { "tipo", TipoMensaje.KICK }, { "codigo", UsuarioActual }
            }).ConfigureAwait(false);
            LanzarSiError(resp, "no se pudieron cerrar las otras sesiones", TipoMensaje.ACK);
        }

        /// <summary>True si salio por red (ACK); False si quedo en pendientes.
        /// Persiste PENDIENTE antes de la red con el ID estable, para que un acuse
        /// rápido (ENTREGADO/LEIDO) que llegue durante la espera correlacione por
        /// el mismo ID y no se pierda: el Upsert posterior nunca retrocede estado.
        /// </summary>
        public async Task<bool> EnviarTextoAsync(string destinatario, string contenido)
        {
            ExigirSesion(destinatario, contenido);
            RechazarDestinoPropio(destinatario);
            var id = Guid.NewGuid().ToString();
            var fecha = DateTime.UtcNow.ToString("O");
            // Fila local primero: cubre la carrera del acuse.
            Historial?.UpsertMensaje(id, UsuarioActual!, destinatario,
                TipoMensaje.MENSAJE_TEXTO, contenido, null, null, null,
                null, null, null, fecha, 1, 1, "PENDIENTE");
            var msg = new Dictionary<string, object?>
            {
                { "id", id },
                { "tipo", TipoMensaje.MENSAJE_TEXTO },
                { "remitente", UsuarioActual },
                { "destinatario", destinatario },
                { "contenido", contenido },
                { "fechaHora", fecha }
            };
            try
            {
            var resp = await _conexion!.Pedir(msg).ConfigureAwait(false);
                LanzarSiError(resp, "envio rechazado", TipoMensaje.ACK);
                Historial?.UpsertMensaje(id, UsuarioActual!, destinatario,
                    TipoMensaje.MENSAJE_TEXTO, contenido,
                    GetString(resp, "hashSha256"), GetInt(resp, "numCaracteres"),
                    GetInt(resp, "numPalabras"), null, null, null, fecha, 1, 1, "ENVIADO");
                return true;
            }
            catch (Exception e) when (e is TimeoutException || e is SocketException || e is IOException)
            {
                Historial?.RegistrarPendiente(id, TipoMensaje.MENSAJE_TEXTO, UsuarioActual!,
                    destinatario, contenido, null, null, fecha);
                return false;
            }
        }

        public async Task<bool> EnviarImagenAsync(string destinatario, string imagePath)
        {
            if (string.IsNullOrEmpty(destinatario)) throw new ArgumentException("elige un destinatario");
            if (_conexion == null || UsuarioActual == null) throw new InvalidOperationException("inicia sesion primero");
            RechazarDestinoPropio(destinatario);
            byte[] bytes = await File.ReadAllBytesAsync(imagePath).ConfigureAwait(false);
            if (bytes.Length == 0) throw new ArgumentException("la imagen esta vacia");
            var id = Guid.NewGuid().ToString();
            var fecha = DateTime.UtcNow.ToString("O");
            var nombre = Path.GetFileName(imagePath);
            Historial?.UpsertMensaje(id, UsuarioActual, destinatario,
                TipoMensaje.MENSAJE_IMAGEN, "[Imagen]", null, null, null,
                nombre, imagePath, bytes.Length, fecha, 1, 1, "PENDIENTE");
            var msg = new Dictionary<string, object?>
            {
                { "id", id },
                { "tipo", TipoMensaje.MENSAJE_IMAGEN },
                { "remitente", UsuarioActual },
                { "destinatario", destinatario },
                { "contenidoImagen", Convert.ToBase64String(bytes) },
                { "nombreArchivo", nombre },
                { "tamanoArchivo", bytes.Length },
                { "fechaHora", fecha }
            };
            try
            {
                var resp = await _conexion.Pedir(msg).ConfigureAwait(false);
                LanzarSiError(resp, "envio rechazado", TipoMensaje.IMAGE_FILTERED_RESULT);
                Historial?.UpsertMensaje(id, UsuarioActual, destinatario,
                    TipoMensaje.MENSAJE_IMAGEN, "[Imagen]", GetString(resp, "hashSha256"),
                    null, null, nombre, imagePath, bytes.Length, fecha, 1, 1,
                    "ENVIADO", GetString(resp, "archivoId"));
                return true;
            }
            catch (Exception e) when (e is TimeoutException || e is SocketException || e is IOException)
            {
                Historial?.RegistrarPendiente(id, TipoMensaje.MENSAJE_IMAGEN, UsuarioActual,
                    destinatario, null, nombre, bytes, fecha);
                return false;
            }
        }

        public const long MaxBytesArchivo = 52_428_800L;
        public const int ParteArchivoBytes = 1024 * 1024;

        /// <summary>
        /// Envía un archivo genérico fragmentado (tope propio 50 MB, PROTOCOLO.md §3/§5):
        /// INICIO + PARTES base64 ≤1 MiB secuenciales + FIN con sha256. Persiste
        /// PENDIENTE con el ID estable antes de la red y reutiliza el mismo ID
        /// en reintentos (el servidor reinicia el buffer ante el mismo id).
        /// </summary>
        public async Task<bool> EnviarArchivoAsync(string destinatario, string rutaArchivo,
            IProgress<double>? progreso = null, CancellationToken cancelacion = default)
        {
            if (string.IsNullOrEmpty(destinatario)) throw new ArgumentException("elige un destinatario");
            if (_conexion == null || UsuarioActual == null) throw new InvalidOperationException("inicia sesion primero");
            RechazarDestinoPropio(destinatario);
            if (string.IsNullOrEmpty(rutaArchivo) || !File.Exists(rutaArchivo))
                throw new ArgumentException("archivo no encontrado");
            byte[] bytes = await File.ReadAllBytesAsync(rutaArchivo, cancelacion).ConfigureAwait(false);
            if (bytes.Length == 0) throw new ArgumentException("el archivo esta vacio");
            if (bytes.Length > MaxBytesArchivo) throw new ArgumentException(
                $"el archivo supera el maximo de {MaxBytesArchivo} bytes");
            var id = Guid.NewGuid().ToString();
            var fecha = DateTime.UtcNow.ToString("O");
            var nombre = Path.GetFileName(rutaArchivo);
            Historial?.UpsertMensaje(id, UsuarioActual, destinatario,
                TipoMensaje.MENSAJE_ARCHIVO, $"[Archivo: {nombre}]", null, null, null,
                nombre, rutaArchivo, bytes.Length, fecha, 1, 1, "PENDIENTE");
            try
            {
                await EnviarFragmentosAsync(destinatario, nombre, MimePorExtension(rutaArchivo),
                    bytes, id, fecha, progreso, cancelacion).ConfigureAwait(false);
                Historial?.UpsertMensaje(id, UsuarioActual, destinatario,
                    TipoMensaje.MENSAJE_ARCHIVO, $"[Archivo: {nombre}]", null, null, null,
                    nombre, rutaArchivo, bytes.Length, fecha, 1, 1, "ENVIADO");
                return true;
            }
            catch (Exception e) when (e is TimeoutException || e is SocketException || e is IOException)
            {
                Historial?.RegistrarPendiente(id, TipoMensaje.MENSAJE_ARCHIVO, UsuarioActual,
                    destinatario, null, nombre, bytes, fecha);
                return false;
            }
        }

        private async Task EnviarFragmentosAsync(string destinatario, string nombre, string mime,
            byte[] bytes, string id, string fecha, IProgress<double>? progreso,
            CancellationToken cancelacion)
        {
            int totalPartes = (bytes.Length + ParteArchivoBytes - 1) / ParteArchivoBytes;
            var inicio = await _conexion!.Pedir(new Dictionary<string, object?>
            {
                { "id", id },
                { "tipo", TipoMensaje.ARCHIVO_INICIO },
                { "remitente", UsuarioActual },
                { "destinatario", destinatario },
                { "nombreArchivo", nombre },
                { "mime", mime },
                { "tamanoArchivo", (long)bytes.Length },
                { "totalPartes", totalPartes },
                { "fechaHora", fecha }
            }).ConfigureAwait(false);
            LanzarSiError(inicio, "envio rechazado", TipoMensaje.ACK);
            for (int i = 0; i < totalPartes; i++)
            {
                cancelacion.ThrowIfCancellationRequested();
                int desde = i * ParteArchivoBytes;
                int hasta = Math.Min(desde + ParteArchivoBytes, bytes.Length);
                string b64 = Convert.ToBase64String(bytes, desde, hasta - desde);
                var parte = await _conexion.Pedir(new Dictionary<string, object?>
                {
                    { "id", id },
                    { "tipo", TipoMensaje.ARCHIVO_PARTE },
                    { "remitente", UsuarioActual },
                    { "archivoId", id },
                    { "indiceParte", i },
                    { "contenidoImagen", b64 },
                    { "fechaHora", fecha }
                }).ConfigureAwait(false);
                LanzarSiError(parte, "envio rechazado", TipoMensaje.ACK);
                progreso?.Report((double)(i + 1) / totalPartes);
            }
            string hash;
            using (var sha = System.Security.Cryptography.SHA256.Create())
                hash = Convert.ToHexString(sha.ComputeHash(bytes)).ToLowerInvariant();
            var fin = await _conexion.Pedir(new Dictionary<string, object?>
            {
                { "id", id },
                { "tipo", TipoMensaje.ARCHIVO_FIN },
                { "remitente", UsuarioActual },
                { "archivoId", id },
                { "hashSha256", hash },
                { "fechaHora", fecha }
            }).ConfigureAwait(false);
            LanzarSiError(fin, "envio rechazado", TipoMensaje.ACK);
        }

        private static string MimePorExtension(string ruta) =>
            Path.GetExtension(ruta).ToLowerInvariant() switch
            {
                ".pdf" => "application/pdf",
                ".png" => "image/png",
                ".jpg" or ".jpeg" => "image/jpeg",
                ".gif" => "image/gif",
                ".bmp" => "image/bmp",
                ".txt" => "text/plain",
                _ => "application/octet-stream"
            };

        public async Task DifundirAsync(string contenido)
        {
            if (_conexion == null || UsuarioActual == null) throw new InvalidOperationException("inicia sesion primero");
            if (string.IsNullOrWhiteSpace(contenido)) throw new ArgumentException("el contenido no puede estar vacio");
            var id = Guid.NewGuid().ToString();
            var fecha = DateTime.UtcNow.ToString("O");
            var resp = await _conexion.Pedir(new Dictionary<string, object?>
            {
                { "id", id },
                { "tipo", TipoMensaje.BROADCAST },
                { "remitente", UsuarioActual },
                { "contenido", contenido },
                { "fechaHora", fecha }
            }).ConfigureAwait(false);
            LanzarSiError(resp, "difusion rechazada", TipoMensaje.ACK);
            Historial?.UpsertMensaje(id, UsuarioActual, DestinoBroadcast,
                TipoMensaje.BROADCAST, contenido, null, null, null,
                null, null, null, fecha, 1, 1);
        }

        /// <summary>
        /// DESCARGAR_ARCHIVO bajo demanda: respuesta unica para archivos chicos y
        /// secuencia INICIO+PARTES+FIN reensamblada para archivos grandes (&gt;1 MiB).
        /// </summary>
        public async Task<string> DescargarArchivoAsync(string idMensaje, string archivoId, string rutaDestino)
        {
            if (_conexion == null) throw new InvalidOperationException("ConectarAsync primero");
            var colector = new ColectorFragmentos(null);
            void AlPush(Dictionary<string, JsonElement> msg) => colector.AlPush(msg);
            _conexion.OnMensajeRecibido += AlPush;
            try
            {
                var resp = await _conexion.Pedir(new Dictionary<string, object?>
                {
                    { "tipo", TipoMensaje.DESCARGAR_ARCHIVO },
                    { "archivoId", archivoId },
                    { "fechaHora", DateTime.UtcNow.ToString("O") }
                });
                if ((GetString(resp, "tipo") == TipoMensaje.ARCHIVO_INICIO))
                {
                    string? idT = GetString(resp, "id");
                    if (idT == null) throw new InvalidOperationException("descarga sin id");
                    colector.FijarId(idT);
                    colector.AlPush(resp);
                    byte[] bytes = await colector.EsperarAsync(TimeSpan.FromMinutes(5))
                        .ConfigureAwait(false);
                    await File.WriteAllBytesAsync(rutaDestino, bytes).ConfigureAwait(false);
                    Historial?.MarcarDescargado(idMensaje, rutaDestino);
                    return rutaDestino;
                }
                LanzarSiError(resp, "descarga rechazada", TipoMensaje.DESCARGAR_ARCHIVO_RESPUESTA);
                string b64 = GetString(resp, "contenidoImagen") ?? "";
                await File.WriteAllBytesAsync(rutaDestino, Convert.FromBase64String(b64))
                    .ConfigureAwait(false);
                Historial?.MarcarDescargado(idMensaje, rutaDestino);
                return rutaDestino;
            }
            finally
            {
                _conexion.OnMensajeRecibido -= AlPush;
            }
        }

        /// <summary>Reensambla PARTES de una descarga correlacionadas por id.</summary>
        private sealed class ColectorFragmentos
        {
            private readonly object _bloqueo = new();
            private readonly Dictionary<int, byte[]> _partes = new();
            private int _total = -1;
            private string? _hash;
            private string? _idEsperado;
            private readonly TaskCompletionSource<byte[]> _listo = new(
                TaskCreationOptions.RunContinuationsAsynchronously);

            public ColectorFragmentos(string? idEsperado) { _idEsperado = idEsperado; }

            public void FijarId(string id)
            {
                lock (_bloqueo) { _idEsperado ??= id; }
            }

            public void AlPush(Dictionary<string, JsonElement> msg)
            {
                lock (_bloqueo)
                {
                    if (_idEsperado != null && GetString(msg, "id") != _idEsperado) return;
                }
                string? tipo = GetString(msg, "tipo");
                try
                {
                    if (tipo == TipoMensaje.ARCHIVO_INICIO)
                    {
                        lock (_bloqueo)
                        {
                            if (msg.TryGetValue("totalPartes", out var tp)
                                && tp.ValueKind == JsonValueKind.Number
                                && tp.TryGetInt32(out int n)) _total = n;
                        }
                    }
                    else if (tipo == TipoMensaje.ARCHIVO_PARTE)
                    {
                        if (!msg.TryGetValue("indiceParte", out var ip)
                            || ip.ValueKind != JsonValueKind.Number
                            || !ip.TryGetInt32(out int indice)) return;
                        string? b64 = GetString(msg, "contenidoImagen");
                        if (b64 == null) return;
                        byte[] bytes = Convert.FromBase64String(b64);
                        bool completa = false;
                        lock (_bloqueo)
                        {
                            _partes[indice] = bytes;
                            completa = _total > 0 && _partes.Count == _total && _hash != null;
                        }
                        if (completa) Completar();
                    }
                    else if (tipo == TipoMensaje.ARCHIVO_FIN)
                    {
                        bool completa = false;
                        lock (_bloqueo)
                        {
                            _hash = GetString(msg, "hashSha256");
                            completa = _total > 0 && _partes.Count == _total && _hash != null;
                        }
                        if (completa) Completar();
                    }
                }
                catch { }
            }

            private void Completar()
            {
                try
                {
                    List<int> orden;
                    lock (_bloqueo) { orden = new List<int>(_partes.Keys); }
                    orden.Sort();
                    using var ms = new MemoryStream();
                    foreach (int i in orden) ms.Write(_partes[i], 0, _partes[i].Length);
                    byte[] todo = ms.ToArray();
                    string hash;
                    using (var sha = System.Security.Cryptography.SHA256.Create())
                        hash = Convert.ToHexString(sha.ComputeHash(todo)).ToLowerInvariant();
                    lock (_bloqueo)
                    {
                        if (_hash != null && !hash.Equals(_hash, StringComparison.OrdinalIgnoreCase))
                        {
                            _listo.TrySetException(new InvalidOperationException(
                                "sha256 de la descarga no coincide"));
                            return;
                        }
                    }
                    _listo.TrySetResult(todo);
                }
                catch (Exception e)
                {
                    _listo.TrySetException(e);
                }
            }

            public async Task<byte[]> EsperarAsync(TimeSpan espera)
            {
                using var cts = new CancellationTokenSource(espera);
                using (cts.Token.Register(() => _listo.TrySetException(
                    new TimeoutException("descarga fragmentada incompleta"))))
                {
                    return await _listo.Task.ConfigureAwait(false);
                }
            }
        }

        public string? ContenidoImagenLocal(string idMensaje) => Historial?.ContenidoPorId(idMensaje);

        /// <summary>Historial offline en orden cronologico (el contrato va DESC).</summary>
        public List<Dictionary<string, object>> CargarHistorial(string otroUsuario, int offset = 0)
        {
            if (UsuarioActual == null) throw new InvalidOperationException("inicia sesion primero");
            var pagina = Historial?.PaginaConversacion(UsuarioActual, otroUsuario, 50, offset)
                ?? new List<Dictionary<string, object>>();
            pagina.Reverse();
            return pagina;
        }

        public async Task<int> TraerHistorialAsync(string otroUsuario, int pagina)
        {
            if (_conexion == null || UsuarioActual == null)
                throw new InvalidOperationException("inicia sesion primero");
            var resp = await _conexion.Pedir(new Dictionary<string, object?>
            {
                { "tipo", TipoMensaje.HISTORIAL_REQ }, { "remitente", UsuarioActual },
                { "destinatario", otroUsuario }, { "pagina", pagina }
            }).ConfigureAwait(false);
            LanzarSiError(resp, "historial rechazado", TipoMensaje.HISTORIAL_PAGE);
            string json = GetString(resp, "contenido") ?? "[]";
            using var document = JsonDocument.Parse(json);
            foreach (var item in document.RootElement.EnumerateArray())
            {
                string? id = Texto(item, "id");
                string? origen = Texto(item, "remitente");
                string? destino = Texto(item, "destinatario");
                string? tipo = Texto(item, "tipo");
                if (string.IsNullOrEmpty(id) || string.IsNullOrEmpty(origen)
                    || string.IsNullOrEmpty(destino) || string.IsNullOrEmpty(tipo)) continue;
                bool propio = origen == UsuarioActual;
                Historial?.UpsertMensaje(id, origen, destino, tipo,
                    tipo == TipoMensaje.MENSAJE_IMAGEN ? "[Imagen]"
                    : tipo == TipoMensaje.MENSAJE_ARCHIVO
                        ? $"[Archivo: {Texto(item, "nombreArchivo") ?? "archivo"}]"
                        : Texto(item, "contenido"),
                    Texto(item, "hashSha256"), Numero(item, "numCaracteres"),
                    Numero(item, "numPalabras"), Texto(item, "nombreArchivo"), null,
                    NumeroLong(item, "tamanoArchivo"), Texto(item, "fechaEnvio")
                        ?? DateTime.UtcNow.ToString("O"), propio ? 1 : 0, 0,
                    propio ? "ENVIADO" : "ENTREGADO", Texto(item, "archivoId"));
            }
            return GetInt(resp, "totalPaginas") ?? 0;
        }

        public int ContarConversacion(string otro) =>
            UsuarioActual == null ? 0 : (Historial?.ContarConversacion(UsuarioActual, otro) ?? 0);

        public List<Dictionary<string, object>> DirectorioLocal() =>
            Cache?.Listar() ?? new List<Dictionary<string, object>>();

        public List<Dictionary<string, object?>> Pendientes() =>
            Historial?.ListarPendientes() ?? new List<Dictionary<string, object?>>();

        public async Task<int> ReintentarPendientesAsync()
        {
            if (UsuarioActual == null || _conexion == null) return 0;
            int enviados = 0;
            foreach (var p in Pendientes())
            {
                if ((string?)p["tipo"] == TipoMensaje.MENSAJE_LEIDO)
                {
                    try
                    {
                        _conexion.Enviar(new Dictionary<string, object?>
                        {
                            { "id", p["id"] }, { "tipo", TipoMensaje.MENSAJE_LEIDO },
                            { "remitente", p["origen"] }, { "destinatario", p["destino"] },
                            { "contenido", p["contenido"] }, { "fechaHora", p["fechaCreado"] }
                        });
                        Historial?.EliminarPendiente((string)p["id"]!);
                        enviados++;
                    }
                    catch (Exception e)
                    {
                        Historial?.IncrementarIntento((string)p["id"]!, e.Message);
                        break;
                    }
                    continue;
                }
                var req = new Dictionary<string, object?>
                {
                    { "id", p["id"] },
                    { "tipo", p["tipo"] },
                    { "remitente", p["origen"] },
                    { "destinatario", p["destino"] },
                    { "fechaHora", p["fechaCreado"] }
                };
                if ((string?)p["tipo"] == TipoMensaje.MENSAJE_ARCHIVO)
                {
                    try
                    {
                        var payload = (byte[]?)p["payload"] ?? Array.Empty<byte>();
                        string nombre = (string?)p["nombreArchivo"] ?? "archivo.bin";
                        await EnviarFragmentosAsync((string)p["destino"]!,
                            nombre, MimePorExtension(nombre), payload, (string)p["id"]!,
                            (string?)p["fechaCreado"] ?? DateTime.UtcNow.ToString("O"),
                            null, default).ConfigureAwait(false);
                        Historial?.MarcarEstado((string)p["id"]!, "ENVIADO");
                        Historial?.EliminarPendiente((string)p["id"]!);
                        enviados++;
                    }
                    catch (Exception e)
                    {
                        Historial?.IncrementarIntento((string)p["id"]!, e.Message);
                        break;
                    }
                    continue;
                }
                if ((string?)p["tipo"] == TipoMensaje.MENSAJE_IMAGEN)
                {
                    var payload = (byte[]?)p["payload"] ?? Array.Empty<byte>();
                    req["contenidoImagen"] = Convert.ToBase64String(payload);
                    req["nombreArchivo"] = p["nombreArchivo"];
                }
                else
                {
                    req["contenido"] = p["contenido"];
                }
                try
                {
                    var resp = await _conexion.Pedir(req).ConfigureAwait(false);
                    LanzarSiError(resp, "reintento rechazado");
                    if ((string?)p["tipo"] == TipoMensaje.MENSAJE_IMAGEN
                        && GetString(resp, "archivoId") is string archivoId)
                        Historial?.ActualizarArchivoId((string)p["id"]!, archivoId);
                    Historial?.MarcarEstado((string)p["id"]!, "ENVIADO");
                    Historial?.EliminarPendiente((string)p["id"]!);
                    enviados++;
                }
                catch (Exception e)
                {
                    Historial?.IncrementarIntento((string)p["id"]!, e.Message);
                    break;
                }
            }
            return enviados;
        }

        private void AlRecibir(Dictionary<string, JsonElement> msg)
        {
            if (!msg.TryGetValue("tipo", out var t) || t.ValueKind != JsonValueKind.String) return;
            string tipo = t.GetString()!;
            if (tipo == TipoMensaje.MENSAJE_ENTREGADO || tipo == TipoMensaje.MENSAJE_LEIDO)
            {
                string? idMensaje = GetString(msg, "contenido");
                if (idMensaje != null)
                    Historial?.MarcarEstado(idMensaje,
                        tipo == TipoMensaje.MENSAJE_LEIDO ? "LEIDO" : "ENTREGADO");
                OnMensajeRecibido?.Invoke(msg);
                return;
            }
            if (tipo == TipoMensaje.PRESENCIA)
            {
                var conectados = new List<string>();
                if (msg.TryGetValue("usuariosConectados", out var lista)
                    && lista.ValueKind == JsonValueKind.Array)
                {
                    foreach (var item in lista.EnumerateArray())
                        if (item.ValueKind == JsonValueKind.String && item.GetString() is string codigo)
                            conectados.Add(codigo);
                }
                Cache?.MarcarConectados(conectados);
                if (msg.TryGetValue("codigo", out var codigoMsg)
                    && codigoMsg.ValueKind == JsonValueKind.String)
                    AsegurarEnCache(codigoMsg.GetString()!);
                OnPresencia?.Invoke(msg);
                return;
            }
            if (tipo == TipoMensaje.ARCHIVO_INICIO || tipo == TipoMensaje.ARCHIVO_PARTE)
                return; // Partes de descarga: las consume el colector de DescargarArchivoAsync.
            if (tipo == TipoMensaje.ARCHIVO_FIN)
            {
                OnMensajeRecibido?.Invoke(msg);
                return;
            }
            if (tipo != TipoMensaje.MENSAJE_TEXTO && tipo != TipoMensaje.MENSAJE_IMAGEN
                && tipo != TipoMensaje.MENSAJE_ARCHIVO
                && tipo != TipoMensaje.BROADCAST && tipo != TipoMensaje.SYNC_LOGIN) return;
            try
            {
                // Lo entrante siempre es (remitente -> yo): el broadcast aparece
                // en la conversacion con su remitente (leccion Fase 5).
                string remitente = GetString(msg, "remitente") ?? "desconocido";
                string id = GetString(msg, "id") ?? Guid.NewGuid().ToString();
                string fecha = GetString(msg, "fechaHora") ?? DateTime.UtcNow.ToString("O");
                bool esImagen = tipo == TipoMensaje.MENSAJE_IMAGEN;
                bool esArchivo = tipo == TipoMensaje.MENSAJE_ARCHIVO;
                string? texto = esImagen ? "[Imagen]"
                    : esArchivo ? $"[Archivo: {GetString(msg, "nombreArchivo") ?? "archivo"}]"
                    : GetString(msg, "contenido");
                Historial?.UpsertMensaje(id, remitente, UsuarioActual ?? remitente, tipo,
                    texto,
                    GetString(msg, "hashSha256"), GetInt(msg, "numCaracteres"),
                    GetInt(msg, "numPalabras"), GetString(msg, "nombreArchivo"),
                    null, GetLong(msg, "tamanoArchivo"), fecha, 0,
                    tipo == TipoMensaje.MENSAJE_TEXTO ? 1 : 0,
                    "ENTREGADO", GetString(msg, "archivoId"));
                AsegurarEnCache(remitente);
            }
            catch { }
            OnMensajeRecibido?.Invoke(msg);
        }

        private void ExigirSesion(string destinatario, string contenido)
        {
            if (_conexion == null || UsuarioActual == null)
                throw new InvalidOperationException("inicia sesion primero");
            if (string.IsNullOrWhiteSpace(destinatario))
                throw new ArgumentException("elige un destinatario");
            if (string.IsNullOrWhiteSpace(contenido))
                throw new ArgumentException("el contenido no puede estar vacio");
        }

        private void RechazarDestinoPropio(string destinatario)
        {
            if (UsuarioActual != null && destinatario == UsuarioActual)
                throw new ArgumentException("elige un destinatario distinto a tu cuenta");
        }

        private void AsegurarEnCache(string codigo)
        {
            if (Cache?.PorCodigo(codigo) == null)
            {
                string ahora = DateTime.UtcNow.ToString("O");
                Cache?.Guardar(codigo, codigo, "", null, 0, ahora, ahora);
            }
        }

        private static void LanzarSiError(Dictionary<string, JsonElement> resp,
            string defecto, params string[] okTipos)
        {
            resp.TryGetValue("tipo", out var t);
            string tipo = t.ValueKind == JsonValueKind.String ? t.GetString()! : "";
            bool esOk = okTipos.Length == 0 || Array.IndexOf(okTipos, tipo) >= 0;
            bool exito = !resp.TryGetValue("exito", out var e)
                || e.ValueKind != JsonValueKind.False;
            if (esOk && exito) return;
            string motivo = GetString(resp, "mensajeError") ?? defecto;
            throw new InvalidOperationException(motivo);
        }

        private static string? GetString(Dictionary<string, JsonElement> m, string clave) =>
            m.TryGetValue(clave, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;

        private static string? Texto(JsonElement m, string clave) =>
            m.TryGetProperty(clave, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;

        private static int? Numero(JsonElement m, string clave) =>
            m.TryGetProperty(clave, out var v) && v.ValueKind == JsonValueKind.Number && v.TryGetInt32(out int n) ? n : null;

        private static long? NumeroLong(JsonElement m, string clave) =>
            m.TryGetProperty(clave, out var v) && v.ValueKind == JsonValueKind.Number && v.TryGetInt64(out long n) ? n : null;

        private static int? GetInt(Dictionary<string, JsonElement> m, string clave) =>
            m.TryGetValue(clave, out var v) && v.ValueKind == JsonValueKind.Number && v.TryGetInt32(out int n) ? n : null;

        private static long? GetLong(Dictionary<string, JsonElement> m, string clave) =>
            m.TryGetValue(clave, out var v) && v.ValueKind == JsonValueKind.Number && v.TryGetInt64(out long n) ? n : null;
    }
}
