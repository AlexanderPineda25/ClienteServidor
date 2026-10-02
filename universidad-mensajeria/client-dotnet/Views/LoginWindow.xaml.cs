using System.IO;
using System.Windows;
using Mensajeria.Fachada;

namespace Mensajeria.Views
{
    public partial class LoginWindow : Window
    {
        private readonly IFachadaCliente _fachada;

        public LoginWindow(IFachadaCliente fachada, string aviso = "")
        {
            _fachada = fachada;
            InitializeComponent();
            CargarServidor();
            if (!string.IsNullOrWhiteSpace(aviso))
            {
                txtAviso.Text = "⏳ " + aviso;
                bordeAviso.Visibility = Visibility.Visible;
            }
        }

        private void CargarServidor()
        {
            try
            {
                string host = "localhost";
                string puerto = "5000";
                string ruta = "client.properties";
                if (File.Exists(ruta))
                {
                    foreach (var linea in File.ReadAllLines(ruta))
                    {
                        var t = linea.Trim();
                        if (t.StartsWith("host=")) host = t.Substring(5).Trim();
                        if (t.StartsWith("puerto=")) puerto = t.Substring(7).Trim();
                    }
                }
                txtHost.Text = host;
                txtPuerto.Text = puerto;
                txtServidor.Text = $"Servidor actual: {host}:{puerto} · client.properties (§5.2).";
            }
            catch
            {
                txtServidor.Text = "Servidor: localhost:5000 (client.properties no legible).";
            }
        }

        private async void BtnEntrar_Click(object sender, RoutedEventArgs e)
        {
            if (string.IsNullOrWhiteSpace(txtCodigo.Text) || string.IsNullOrEmpty(txtContrasena.Password))
            {
                txtError.Text = "✖ Ingresa el código y la contraseña.";
                bordeError.Visibility = Visibility.Visible;
                return;
            }
            bordeError.Visibility = Visibility.Collapsed;
            bordeAviso.Visibility = Visibility.Collapsed;
            btnEntrar.IsEnabled = false;
            try
            {
                txtError.Text = "Conectando...";
                bordeError.Visibility = Visibility.Visible;
                await _fachada.ConectarAsync();

                bool ok = await _fachada.LoginAsync(txtCodigo.Text.Trim(), txtContrasena.Password);
                if (ok)
                {
                    var chat = new ChatWindow(_fachada);
                    chat.Show();
                    this.Close();
                }
                else
                {
                    txtError.Text = "✖ Usuario no registrado o datos incorrectos (LOGIN_RESPUESTA exito=false).";
                    bordeError.Visibility = Visibility.Visible;
                    _fachada.Desconectar();
                }
            }
            catch (System.Exception ex)
            {
                txtError.Text = "✖ No se pudo conectar (" + ex.GetType().Name + "): " + ex.Message;
                bordeError.Visibility = Visibility.Visible;
            }
            finally
            {
                btnEntrar.IsEnabled = true;
            }
        }

        // Fase 10.2 (P2): sin «Crear cuenta». La alta de usuarios es solo por
        // archivo plano (usuarios_iniciales.csv); RegistroWindow queda inalcanzable.
    }
}
