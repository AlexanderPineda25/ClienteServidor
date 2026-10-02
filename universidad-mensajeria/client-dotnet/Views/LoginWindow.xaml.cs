using System.Windows;
using Mensajeria.Fachada;

namespace Mensajeria.Views
{
    public partial class LoginWindow : Window
    {
        private readonly IFachadaCliente _fachada;

        public LoginWindow(IFachadaCliente fachada)
        {
            _fachada = fachada;
            InitializeComponent();
        }

        private async void BtnEntrar_Click(object sender, RoutedEventArgs e)
        {
            try
            {
                txtError.Text = "Conectando...";
                await _fachada.ConectarAsync();
                
                bool ok = await _fachada.LoginAsync(txtCodigo.Text, txtContrasena.Password);
                if (ok)
                {
                    var chat = new ChatWindow(_fachada);
                    chat.Show();
                    this.Close();
                }
                else
                {
                    txtError.Text = "Credenciales inválidas";
                    _fachada.Desconectar();
                }
            }
            catch (System.Exception ex)
            {
                txtError.Text = "Error: " + ex.Message;
            }
        }

        // Fase 10.2 (P2): sin «Crear cuenta». La alta de usuarios es solo por
        // archivo plano (usuarios_iniciales.csv); RegistroWindow queda inalcanzable.
    }
}
