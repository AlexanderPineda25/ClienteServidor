using System.Collections.Generic;

namespace Mensajeria.Componentes
{
    /// <summary>Contrato de cache de usuarios (HISTORIAL_LOCAL.md §6-7).</summary>
    public interface ICacheUsuarios
    {
        void Guardar(string codigo, string nombres, string apellidos, string? programa,
            int conectado, string? fechaRegistro, string actualizado);
        List<Dictionary<string, object>> Listar();
        Dictionary<string, object>? PorCodigo(string codigo);
        void MarcarConectados(List<string> codigos);
    }
}
