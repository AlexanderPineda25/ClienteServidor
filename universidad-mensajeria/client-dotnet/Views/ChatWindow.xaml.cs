using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.IO;
using System.Linq;
using System.Runtime.CompilerServices;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Microsoft.Win32;
using Mensajeria.Fachada;
using Mensajeria.Transversal;

namespace Mensajeria.Views
{
    public class UsuarioVM
    {
        public string Codigo { get; init; } = "";
        public string Nombres { get; init; } = "";
        public string Apellidos { get; init; } = "";
        public bool Conectado { get; init; }
        public string Etiqueta => string.IsNullOrWhiteSpace((Nombres + " " + Apellidos).Trim())
            ? Codigo : $"{Nombres} {Apellidos} [{Codigo}]";
        public string Presencia => Conectado ? "en línea" : "desconectado";
        public Brush ColorPresencia => Conectado ? Brushes.SeaGreen : Brushes.LightGray;
    }

    public class MensajeVM : INotifyPropertyChanged
    {
        private BitmapImage? _imagen;
        private string _estadoImagen = "";
        private bool _cargando;
        private bool _solicitada;

        public string Id { get; init; } = "";
        public string Encabezado { get; init; } = "";
        public string Contenido { get; init; } = "";
        public string Estado { get; init; } = "";
        public string ArchivoId { get; init; } = "";
        public string RutaArchivo { get; set; } = "";
        public string NombreArchivo { get; init; } = "";
        public bool EsImagen { get; init; }
        public bool EsArchivo { get; init; }
        public Visibility MostrarDescarga => EsArchivo ? Visibility.Visible : Visibility.Collapsed;
        /// <summary>True si el remitente es la sesión activa: burbuja a la derecha.</summary>
        public bool EsPropio { get; init; }
        public bool EsLeido { get; init; }
        public HorizontalAlignment Alineacion => EsPropio ? HorizontalAlignment.Right : HorizontalAlignment.Left;
        public Brush Fondo => EsPropio ? new SolidColorBrush(Color.FromRgb(0xD9, 0xFD, 0xD3)) : Brushes.White;
        public Brush ColorEstado => EsLeido ? new SolidColorBrush(Color.FromRgb(0x1D, 0x9B, 0xF0)) : new SolidColorBrush(Color.FromRgb(0x61, 0x71, 0x7F));
        public BitmapImage? Imagen { get => _imagen; set { _imagen = value; OnPropertyChanged(); OnPropertyChanged(nameof(MostrarReintento)); } }
        public string EstadoImagen { get => _estadoImagen; set { _estadoImagen = value; OnPropertyChanged(); } }
        public bool Cargando { get => _cargando; set { _cargando = value; OnPropertyChanged(); OnPropertyChanged(nameof(MostrarReintento)); } }
        public bool Solicitada { get => _solicitada; set { _solicitada = value; OnPropertyChanged(); OnPropertyChanged(nameof(MostrarReintento)); } }
        public Visibility MostrarTexto => string.IsNullOrEmpty(Contenido) ? Visibility.Collapsed : Visibility.Visible;
        public Visibility MostrarImagen => EsImagen ? Visibility.Visible : Visibility.Collapsed;
        public Visibility MostrarReintento => EsImagen && !Cargando && Imagen == null && Solicitada
            ? Visibility.Visible : Visibility.Collapsed;
        public event PropertyChangedEventHandler? PropertyChanged;
        private void OnPropertyChanged([CallerMemberName] string? name = null) =>
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    }

    public partial class ChatWindow : Window
    {
        private readonly IFachadaCliente _fachada;
        private readonly ObservableCollection<UsuarioVM> _usuarios = new();
        private readonly ObservableCollection<MensajeVM> _mensajes = new();
        private List<UsuarioVM> _directorio = new();
        private string? _usuarioDestino;
        private string? _imagenPendiente;
        private string? _archivoPendiente;
        private bool _cerrando;
        private int _offsetActual;
        private readonly Dictionary<string, int> _paginaRemota = new();
        private readonly Dictionary<string, int> _totalPaginasRemotas = new();
        private readonly SemaphoreSlim _limiteImagenes = new(3, 3);

        public ChatWindow(IFachadaCliente fachada)
        {
            _fachada = fachada;
            InitializeComponent();
            txtSesion.Text = $"Sesión activa · {_fachada.UsuarioActual}";
            lstUsuarios.ItemsSource = _usuarios;
            lstMensajes.ItemsSource = _mensajes;
            _fachada.OnMensajeRecibido += Fachada_OnMensajeRecibido;
            _fachada.OnCierre += Fachada_OnCierre;
            _fachada.OnPresencia += Fachada_OnPresencia;
            CargarUsuarios();
            ActualizarEstadoEnvio();
        }

        protected override void OnClosing(CancelEventArgs e)
        {
            if (!_cerrando)
            {
                if (MessageBox.Show("¿Deseas cerrar esta sesión y volver al inicio?", "Confirmar salida",
                        MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes)
                {
                    e.Cancel = true;
                    return;
                }
                VolverAlInicio();
            }
            base.OnClosing(e);
        }

        private void VolverAlInicio()
        {
            _cerrando = true;
            _fachada.OnMensajeRecibido -= Fachada_OnMensajeRecibido;
            _fachada.OnCierre -= Fachada_OnCierre;
            _fachada.OnPresencia -= Fachada_OnPresencia;
            _fachada.Desconectar();
            new LoginWindow(_fachada).Show();
        }

        private void Fachada_OnCierre(Dictionary<string, JsonElement> aviso)
        {
            string motivo = GetString(aviso, "mensajeError") ?? "sesión cerrada por el servidor";
            Dispatcher.BeginInvoke(() =>
            {
                if (_cerrando) return;
                MessageBox.Show(motivo, "Sesión cerrada");
                VolverAlInicio();
                Close();
            });
        }

        private async void CargarUsuarios()
        {
            List<Dictionary<string, object>> lista;
            try { lista = await _fachada.ListarUsuariosAsync(); }
            catch { lista = await Task.Run(() => _fachada.DirectorioLocal()); }
            string? seleccionado = (lstUsuarios.SelectedItem as UsuarioVM)?.Codigo;
            // Encabezado de sesión con nombre completo + código cuando está disponible.
            var propia = lista.FirstOrDefault(u => u.TryGetValue("codigo", out var c) && c is string s && s == _fachada.UsuarioActual);
            if (propia != null)
            {
                string nom = (Valor(propia, "nombres") + " " + Valor(propia, "apellidos")).Trim();
                txtSesion.Text = string.IsNullOrWhiteSpace(nom)
                    ? $"Sesión activa · {_fachada.UsuarioActual}"
                    : $"Sesión activa · {nom} [{_fachada.UsuarioActual}]";
            }
            _directorio = lista
                .Where(u => u.TryGetValue("codigo", out var c) && c is string s && s != _fachada.UsuarioActual)
                .Select(u => new UsuarioVM
                {
                    Codigo = Valor(u, "codigo"), Nombres = Valor(u, "nombres"),
                    Apellidos = Valor(u, "apellidos"), Conectado = EsConectado(u)
                }).ToList();
            AplicarFiltro(seleccionado);
        }

        private void AplicarFiltro(string? seleccionado = null)
        {
            string filtro = (txtBuscar.Text ?? "").Trim();
            var vista = _directorio.Where(u =>
                filtro.Length == 0 || u.Etiqueta.Contains(filtro, StringComparison.CurrentCultureIgnoreCase)
                || u.Codigo.Contains(filtro, StringComparison.CurrentCultureIgnoreCase))
                .OrderByDescending(u => u.Conectado).ThenBy(u => u.Apellidos).ToList();
            _usuarios.Clear();
            foreach (var usuario in vista) _usuarios.Add(usuario);
            if (seleccionado != null)
                lstUsuarios.SelectedItem = _usuarios.FirstOrDefault(u => u.Codigo == seleccionado);
            txtEstado.Text = $"{_directorio.Count(u => u.Conectado)} contactos en línea";
        }

        private void TxtBuscar_TextChanged(object sender, TextChangedEventArgs e) => AplicarFiltro(_usuarioDestino);

        private void Fachada_OnPresencia(Dictionary<string, JsonElement> aviso) =>
            Dispatcher.BeginInvoke(CargarUsuarios);

        private void Fachada_OnMensajeRecibido(Dictionary<string, JsonElement> msg)
        {
            if (!msg.TryGetValue("tipo", out var t) || t.ValueKind != JsonValueKind.String) return;
            string tipo = t.GetString()!;
            if (tipo == TipoMensaje.MENSAJE_ENTREGADO || tipo == TipoMensaje.MENSAJE_LEIDO)
            {
                Dispatcher.BeginInvoke(() =>
                {
                    if (_usuarioDestino != null) CargarHistorial(_usuarioDestino);
                });
                return;
            }
            if (tipo != TipoMensaje.MENSAJE_TEXTO && tipo != TipoMensaje.MENSAJE_IMAGEN
                && tipo != TipoMensaje.BROADCAST && tipo != TipoMensaje.SYNC_LOGIN) return;
            string remitente = GetString(msg, "remitente") ?? "desconocido";
            if (_usuarioDestino != remitente && tipo != TipoMensaje.BROADCAST) return;
            Dispatcher.BeginInvoke(() =>
            {
                if (_usuarioDestino != remitente && tipo != TipoMensaje.BROADCAST) return;
                CargarHistorial(tipo == TipoMensaje.BROADCAST ? remitente : _usuarioDestino!);
                lstMensajes.ScrollIntoView(_mensajes.LastOrDefault());
                if (_usuarioDestino == remitente)
                    _ = Task.Run(() => _fachada.MarcarLeidosAsync(remitente));
            });
        }

        private void BtnActualizar_Click(object sender, RoutedEventArgs e) => CargarUsuarios();

        private void LstUsuarios_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            if (lstUsuarios.SelectedItem is not UsuarioVM usuario) return;
            _usuarioDestino = usuario.Codigo;
            _offsetActual = 0;
            txtTitulo.Text = usuario.Etiqueta;
            txtEstado.Text = usuario.Presencia;
            _paginaRemota[usuario.Codigo] = 0;
            _totalPaginasRemotas.Remove(usuario.Codigo);
            CargarHistorialRemoto(usuario.Codigo, 0);
            CargarHistorial(usuario.Codigo);
            _ = Task.Run(() => _fachada.MarcarLeidosAsync(usuario.Codigo));
        }

        private async void CargarHistorial(string codigo, int offset = 0, bool prepend = false)
        {
            List<Dictionary<string, object>> historial;
            int totalLocal;
            try
            {
                (historial, totalLocal) = await Task.Run(() =>
                    (_fachada.CargarHistorial(codigo, offset), _fachada.ContarConversacion(codigo)));
            }
            catch { return; }
            if (_usuarioDestino != codigo) return;
            if (!prepend) _offsetActual = offset;
            if (!prepend) _mensajes.Clear();
            var filas = prepend ? historial.AsEnumerable().Reverse() : historial;
            foreach (var row in filas)
            {
                string tipo = Valor(row, "tipo");
                string remitente = Valor(row, "origen");
                bool esImagen = tipo == TipoMensaje.MENSAJE_IMAGEN;
                bool esArchivo = tipo == TipoMensaje.MENSAJE_ARCHIVO;
                bool propio = remitente == _fachada.UsuarioActual;
                string identidad = propio ? "Tú" : Identidad(remitente);
                string estadoBruto = Valor(row, "estado");
                _mensajes.Add(new MensajeVM
                {
                    Id = Valor(row, "id"),
                    Encabezado = $"{identidad} · {Valor(row, "fechaEnvio")}",
                    Contenido = esImagen ? "" : Valor(row, "contenido"),
                    Estado = propio ? EstadoVisible(estadoBruto) : "",
                    EsImagen = esImagen,
                    EsArchivo = esArchivo,
                    EsPropio = propio,
                    EsLeido = estadoBruto == "LEIDO",
                    ArchivoId = Valor(row, "archivoId"),
                    NombreArchivo = Valor(row, "nombreArchivo"),
                    RutaArchivo = Valor(row, "rutaArchivo")
                });
                if (prepend)
                {
                    var nuevo = _mensajes[^1];
                    _mensajes.RemoveAt(_mensajes.Count - 1);
                    _mensajes.Insert(0, nuevo);
                }
            }
            if (prepend) lstMensajes.ScrollIntoView(_mensajes.FirstOrDefault());
            else lstMensajes.ScrollIntoView(_mensajes.LastOrDefault());
            int paginasRemotas = _totalPaginasRemotas.GetValueOrDefault(codigo);
            btnAnteriores.Visibility = offset + 50 < totalLocal
                || _paginaRemota.GetValueOrDefault(codigo) + 1 < paginasRemotas
                ? Visibility.Visible : Visibility.Collapsed;
        }

        private async void CargarHistorialRemoto(string codigo, int pagina, bool olderPage = false)
        {
            try
            {
                int total = await _fachada.TraerHistorialAsync(codigo, pagina);
                _paginaRemota[codigo] = pagina;
                _totalPaginasRemotas[codigo] = total;
                if (_usuarioDestino == codigo)
                    CargarHistorial(codigo, olderPage ? _offsetActual : 0, olderPage);
            }
            catch
            {
                if (_usuarioDestino == codigo && pagina == 0)
                    txtEstado.Text = "Mostrando historial local";
            }
        }

        private void BtnAnteriores_Click(object sender, RoutedEventArgs e)
        {
            if (_usuarioDestino == null) return;
            string codigo = _usuarioDestino;
            int totalLocal = _fachada.ContarConversacion(codigo);
            if (_offsetActual + 50 < totalLocal)
            {
                _offsetActual += 50;
                CargarHistorial(codigo, _offsetActual, true);
                return;
            }
            int siguiente = _paginaRemota.GetValueOrDefault(codigo) + 1;
            if (siguiente < _totalPaginasRemotas.GetValueOrDefault(codigo))
            {
                _offsetActual = totalLocal;
                CargarHistorialRemoto(codigo, siguiente, true);
            }
        }

        private async void MessageImage_Loaded(object sender, RoutedEventArgs e)
        {
            if (sender is Image { DataContext: MensajeVM vm }) await CargarImagenAsync(vm);
        }

        private async Task CargarImagenAsync(MensajeVM vm)
        {
            if (vm.Imagen != null || vm.Cargando || vm.Solicitada) return;
            vm.Cargando = true;
            vm.EstadoImagen = "Cargando imagen…";
            await _limiteImagenes.WaitAsync();
            try
            {
                var bitmap = await Task.Run(async () =>
                {
                    string ruta = vm.RutaArchivo;
                    if (string.IsNullOrWhiteSpace(ruta) || !File.Exists(ruta))
                    {
                        string extension = Path.GetExtension(vm.NombreArchivo);
                        if (string.IsNullOrWhiteSpace(extension)) extension = ".img";
                        string carpeta = Path.Combine(Environment.GetFolderPath(
                            Environment.SpecialFolder.LocalApplicationData), "Mensajeria", "imagenes");
                        Directory.CreateDirectory(carpeta);
                        ruta = Path.Combine(carpeta, vm.Id + extension);
                        if (!File.Exists(ruta))
                        {
                            if (!string.IsNullOrWhiteSpace(vm.ArchivoId))
                            {
                                await _fachada.DescargarArchivoAsync(vm.Id, vm.ArchivoId, ruta);
                            }
                            else
                            {
                                string? local = _fachada.ContenidoImagenLocal(vm.Id);
                                if (string.IsNullOrWhiteSpace(local) || local == "[Imagen]")
                                    throw new InvalidOperationException("La imagen no tiene archivo disponible.");
                                byte[] imagenLocal = Convert.FromBase64String(local);
                                if (imagenLocal.Length == 0)
                                    throw new InvalidOperationException("La imagen local está vacía.");
                                await File.WriteAllBytesAsync(ruta, imagenLocal);
                            }
                        }
                    }
                    var image = new BitmapImage();
                    image.BeginInit();
                    image.CacheOption = BitmapCacheOption.OnLoad;
                    image.UriSource = new Uri(ruta, UriKind.Absolute);
                    image.EndInit();
                    image.Freeze();
                    return (Image: image, Path: ruta);
                });
                vm.Imagen = bitmap.Image;
                vm.RutaArchivo = bitmap.Path;
                vm.EstadoImagen = vm.NombreArchivo;
            }
            catch (Exception ex)
            {
                vm.EstadoImagen = ex.Message;
                vm.Solicitada = true;
            }
            finally
            {
                _limiteImagenes.Release();
                vm.Cargando = false;
            }
        }

        private async void BtnReintentarImagen_Click(object sender, RoutedEventArgs e)
        {
            if (sender is Button { DataContext: MensajeVM vm })
            {
                vm.Solicitada = false;
                await CargarImagenAsync(vm);
            }
        }

        private void BtnResponder_Click(object sender, RoutedEventArgs e)
        {
            if (sender is not Button { DataContext: MensajeVM vm }) return;
            string autor = vm.Encabezado.Split('·')[0].Trim();
            string extracto = (vm.EsImagen || vm.EsArchivo) && !string.IsNullOrEmpty(vm.NombreArchivo)
                ? vm.NombreArchivo : vm.Contenido;
            if (extracto.Length > 120) extracto = extracto[..120] + "…";
            string cita = $"Respuesta a {autor}: \"{extracto}\"";
            txtRespuesta.Text = cita;
            txtRespuesta.Visibility = Visibility.Visible;
            if (txtMensaje.Text.Length == 0) txtMensaje.Text = cita + "\n";
            else txtMensaje.Text = cita + "\n" + txtMensaje.Text;
            txtMensaje.Focus();
            txtMensaje.CaretIndex = txtMensaje.Text.Length;
            ActualizarEstadoEnvio();
        }

        private async void BtnEnviar_Click(object sender, RoutedEventArgs e)
        {
            if (string.IsNullOrEmpty(_usuarioDestino)) return;
            string texto = (txtMensaje.Text ?? "").Trim();
            bool hayImagen = !string.IsNullOrEmpty(_imagenPendiente);
            bool hayArchivo = !string.IsNullOrEmpty(_archivoPendiente);
            if (texto.Length == 0 && !hayImagen && !hayArchivo) return;
            btnEnviar.IsEnabled = false;
            try
            {
                bool enRed = true;
                string destino = _usuarioDestino;
                if (texto.Length > 0)
                {
                    enRed = await Task.Run(() => _fachada.EnviarTextoAsync(destino, texto));
                    txtMensaje.Clear();
                }
                if (hayImagen)
                {
                    string ruta = _imagenPendiente!;
                    enRed = await Task.Run(() => _fachada.EnviarImagenAsync(destino, ruta)) && enRed;
                    LimpiarAdjunto();
                }
                if (hayArchivo)
                {
                    string ruta = _archivoPendiente!;
                    var progreso = new Progress<double>(p =>
                        txtArchivoAdjunto.Text = $"Enviando {Path.GetFileName(ruta)}… {p:P0}");
                    enRed = await Task.Run(() => _fachada.EnviarArchivoAsync(destino, ruta, progreso)) && enRed;
                    LimpiarAdjuntoArchivo();
                }
                txtRespuesta.Text = "";
                txtRespuesta.Visibility = Visibility.Collapsed;
                if (!enRed) MessageBox.Show("Sin red: guardado en pendientes.", "Offline");
                CargarHistorial(_usuarioDestino);
            }
            catch (Exception ex) { MessageBox.Show(ex.Message, "No se pudo enviar"); }
            finally { ActualizarEstadoEnvio(); }
        }

        private void TxtMensaje_TextChanged(object sender, TextChangedEventArgs e) => ActualizarEstadoEnvio();

        private void TxtMensaje_KeyDown(object sender, System.Windows.Input.KeyEventArgs e)
        {
            if (e.Key == System.Windows.Input.Key.Enter
                && !System.Windows.Input.Keyboard.IsKeyDown(System.Windows.Input.Key.LeftShift)
                && !System.Windows.Input.Keyboard.IsKeyDown(System.Windows.Input.Key.RightShift))
            {
                e.Handled = true;
                BtnEnviar_Click(sender, new RoutedEventArgs());
            }
        }

        private void ActualizarEstadoEnvio() =>
            btnEnviar.IsEnabled = _usuarioDestino != null
                && (!string.IsNullOrWhiteSpace(txtMensaje.Text) || !string.IsNullOrEmpty(_imagenPendiente)
                    || !string.IsNullOrEmpty(_archivoPendiente));

        private void BtnAdjuntar_Click(object sender, RoutedEventArgs e)
        {
            if (string.IsNullOrEmpty(_usuarioDestino)) return;
            var dlg = new OpenFileDialog { Filter = "Imágenes|*.jpg;*.jpeg;*.png;*.gif;*.bmp" };
            if (dlg.ShowDialog() == true)
                FijarImagenPendiente(dlg.FileName);
        }

        private void ZonaEnvio_DragOver(object sender, DragEventArgs e)
        {
            e.Effects = e.Data.GetDataPresent(DataFormats.FileDrop) ? DragDropEffects.Copy : DragDropEffects.None;
            e.Handled = true;
        }

        private void ZonaEnvio_Drop(object sender, DragEventArgs e)
        {
            if (string.IsNullOrEmpty(_usuarioDestino)) return;
            if (!e.Data.GetDataPresent(DataFormats.FileDrop)) return;
            var archivos = (string[]?)e.Data.GetData(DataFormats.FileDrop);
            string? candidato = archivos?.FirstOrDefault(File.Exists);
            if (candidato == null) return;
            if (EsImagenSoportada(candidato)) FijarImagenPendiente(candidato);
            else FijarArchivoPendiente(candidato);
        }

        private static bool EsImagenSoportada(string ruta)
        {
            string ext = Path.GetExtension(ruta).ToLowerInvariant();
            return ext is ".jpg" or ".jpeg" or ".png" or ".gif" or ".bmp";
        }

        private void FijarImagenPendiente(string ruta)
        {
            _imagenPendiente = ruta;
            txtImagenAdjunta.Text = $"Adjunta: {Path.GetFileName(ruta)} (arrastra otra para reemplazar)";
            panelPreview.Visibility = Visibility.Visible;
            ActualizarEstadoEnvio();
            // Decodificación fuera del dispatcher: el bitmap se congela antes de pintarse.
            _ = Task.Run(() =>
            {
                try
                {
                    var bmp = new BitmapImage();
                    bmp.BeginInit();
                    bmp.CacheOption = BitmapCacheOption.OnLoad;
                    bmp.UriSource = new Uri(ruta, UriKind.Absolute);
                    bmp.EndInit();
                    bmp.Freeze();
                    return (BitmapImage?)bmp;
                }
                catch { return null; }
            }).ContinueWith(t =>
            {
                if (t.Result != null) imgPreview.Source = t.Result;
                else txtImagenAdjunta.Text = $"Adjunta: {Path.GetFileName(ruta)} (sin vista previa)";
            }, TaskScheduler.FromCurrentSynchronizationContext());
        }

        private void BtnQuitarImagen_Click(object sender, RoutedEventArgs e) => LimpiarAdjunto();

        private void LimpiarAdjunto()
        {
            _imagenPendiente = null;
            txtImagenAdjunta.Text = "";
            imgPreview.Source = null;
            panelPreview.Visibility = Visibility.Collapsed;
            ActualizarEstadoEnvio();
        }

        private void BtnAdjuntarArchivo_Click(object sender, RoutedEventArgs e)
        {
            if (string.IsNullOrEmpty(_usuarioDestino)) return;
            var dlg = new OpenFileDialog { Filter = "Todos los archivos|*.*" };
            if (dlg.ShowDialog() == true)
                FijarArchivoPendiente(dlg.FileName);
        }

        private void FijarArchivoPendiente(string ruta)
        {
            try
            {
                long tamano = new FileInfo(ruta).Length;
                if (tamano > FachadaCliente.MaxBytesArchivo)
                {
                    MessageBox.Show($"El archivo supera el máximo de 50 MB ({tamano} bytes).",
                        "Adjunto no válido");
                    return;
                }
                _archivoPendiente = ruta;
                txtArchivoAdjunto.Text = $"Archivo: {Path.GetFileName(ruta)} ({tamano} bytes)";
                panelArchivo.Visibility = Visibility.Visible;
                ActualizarEstadoEnvio();
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message, "Adjunto no válido");
            }
        }

        private void BtnQuitarArchivo_Click(object sender, RoutedEventArgs e) => LimpiarAdjuntoArchivo();

        private void LimpiarAdjuntoArchivo()
        {
            _archivoPendiente = null;
            txtArchivoAdjunto.Text = "";
            panelArchivo.Visibility = Visibility.Collapsed;
            ActualizarEstadoEnvio();
        }

        private async void BtnDescargarArchivo_Click(object sender, RoutedEventArgs e)
        {
            if (sender is not Button { DataContext: MensajeVM vm }) return;
            if (string.IsNullOrWhiteSpace(vm.ArchivoId))
            {
                MessageBox.Show("El servidor no entregó el identificador de este archivo.",
                    "Descarga no disponible");
                return;
            }
            var dlg = new SaveFileDialog
            {
                FileName = string.IsNullOrWhiteSpace(vm.NombreArchivo) ? "archivo" : vm.NombreArchivo
            };
            if (dlg.ShowDialog() != true) return;
            try
            {
                string ruta = dlg.FileName;
                await Task.Run(() => _fachada.DescargarArchivoAsync(vm.Id, vm.ArchivoId, ruta));
                vm.RutaArchivo = ruta;
                MessageBox.Show($"Archivo guardado en {ruta}.", "Descarga completa");
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message, "No se pudo descargar");
            }
        }

        private async void BtnDifundir_Click(object sender, RoutedEventArgs e)
        {
            string texto = Microsoft.VisualBasic.Interaction.InputBox("Mensaje de difusión:", "Difundir");
            if (string.IsNullOrWhiteSpace(texto)) return;
            try { await _fachada.DifundirAsync(texto); }
            catch (Exception ex) { MessageBox.Show(ex.Message, "No se pudo difundir"); }
        }

        private void BtnSalir_Click(object sender, RoutedEventArgs e)
        {
            VolverAlInicio();
            Close();
        }

        private async void BtnSalirTodos_Click(object sender, RoutedEventArgs e)
        {
            if (MessageBox.Show("¿Cerrar todas las sesiones de esta cuenta?", "Confirmar salida",
                    MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
            try
            {
                await _fachada.ExpulsarOtrasSesionesAsync();
                VolverAlInicio();
                Close();
            }
            catch (Exception ex) { MessageBox.Show(ex.Message, "No se pudieron cerrar las otras sesiones"); }
        }

        private string Identidad(string codigo)
        {
            var usuario = _directorio.FirstOrDefault(u => u.Codigo == codigo);
            return usuario == null ? codigo : usuario.Etiqueta;
        }

        private static string EstadoVisible(string estado) => estado switch
        {
            // Visto azul: LEIDO se pinta ✓✓ con ColorEstado azul (EsLeido).
            "PENDIENTE" => "Pendiente", "ENVIADO" => "✓", "ENTREGADO" => "✓✓", "LEIDO" => "✓✓", _ => ""
        };

        private static bool EsConectado(Dictionary<string, object> usuario)
        {
            if (!usuario.TryGetValue("conectado", out var c)) return false;
            return c switch
            {
                bool b => b,
                int i => i != 0,
                long l => l != 0,
                _ => false
            };
        }

        private static string Valor(Dictionary<string, object> fila, string clave) =>
            fila.TryGetValue(clave, out var valor) && valor != null ? valor.ToString() ?? "" : "";

        private static string? GetString(Dictionary<string, JsonElement> fila, string clave) =>
            fila.TryGetValue(clave, out var valor) && valor.ValueKind == JsonValueKind.String
                ? valor.GetString() : null;
    }
}
