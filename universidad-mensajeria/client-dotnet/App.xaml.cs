using System.Windows;
using Mensajeria.Fachada;

namespace Mensajeria
{
    public partial class App : Application
    {
        public static IFachadaCliente Fachada { get; private set; } = null!;

        protected override void OnStartup(StartupEventArgs e)
        {
            base.OnStartup(e);
            Fachada = new FachadaCliente();
            new Views.LoginWindow(Fachada).Show();
        }

        protected override void OnExit(ExitEventArgs e)
        {
            Fachada?.Desconectar();
            base.OnExit(e);
        }
    }
}
