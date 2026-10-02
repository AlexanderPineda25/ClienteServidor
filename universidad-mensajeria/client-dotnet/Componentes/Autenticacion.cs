using System;
using System.Collections.Generic;
using System.Text.Json;
using System.Threading.Tasks;
using Mensajeria.Transversal;

namespace Mensajeria.Componentes
{
    public class Autenticacion : IAutenticacion
    {
        private readonly IConexionRed _conexion;
        public string? CodigoActual { get; private set; }

        public Autenticacion(IConexionRed conexion)
        {
            _conexion = conexion;
        }

        public async Task<bool> LoginAsync(string codigo, string contrasena)
        {
            var msg = new Dictionary<string, object?>
            {
                { "tipo", TipoMensaje.LOGIN },
                { "codigo", codigo },
                { "contrasena", contrasena }
            };
            
            var resp = await _conexion.Pedir(msg);
            
            if (resp.TryGetValue("exito", out var exito) && exito.GetBoolean())
            {
                CodigoActual = codigo;
                return true;
            }
            return false;
        }

        public void Logout()
        {
            if (CodigoActual == null) return;
            var msg = new Dictionary<string, object?>
            {
                { "tipo", TipoMensaje.LOGOUT },
                { "codigo", CodigoActual }
            };
            try { _conexion.Enviar(msg); } catch {}
            CodigoActual = null;
        }
    }
}
