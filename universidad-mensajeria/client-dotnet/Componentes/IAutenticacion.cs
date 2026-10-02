using System.Collections.Generic;
using System.Threading.Tasks;

namespace Mensajeria.Componentes
{
    /// <summary>Contrato de sesion (espejo de InterfazAutenticacion Java).</summary>
    public interface IAutenticacion
    {
        string? CodigoActual { get; }
        Task<bool> LoginAsync(string codigo, string contrasena);
        void Logout();
    }
}
