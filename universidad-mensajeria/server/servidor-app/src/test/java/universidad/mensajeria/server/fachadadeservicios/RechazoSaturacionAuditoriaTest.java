package universidad.mensajeria.server.fachadadeservicios;

import org.junit.jupiter.api.Test;
import universidad.mensajeria.colas.InterfazGestorMensajes;
import universidad.mensajeria.common.interno.LimitesDTO;
import universidad.mensajeria.common.interno.TipoAccion;
import universidad.mensajeria.protocolo.InterfazProtocoloComunicacion;
import universidad.mensajeria.server.almacenarinformacion.InterfazAlmacenarInformacion;
import universidad.mensajeria.server.conexionesclientes.InterfazConexionesClientes;
import universidad.mensajeria.server.fachadadeservicios.casosdeuso.Casos;
import universidad.mensajeria.server.logdeeventos.InterfazLogDeEventos;
import universidad.mensajeria.server.mensajes.InterfazMensajes;
import universidad.mensajeria.servicios.InterfazServiciosDisponibles;
import universidad.mensajeria.usuarios.InterfazUsuariosDisponibles;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** El rechazo por pool lleno queda en registro_acciones (RF-S46). */
class RechazoSaturacionAuditoriaTest {

    @Test
    void alRechazoPorSaturacionRegistraLimiteAlcanzado() {
        InterfazLogDeEventos eventos = mock(InterfazLogDeEventos.class);
        FachadaDeServicios fachada = new FachadaDeServicios(
                mock(InterfazProtocoloComunicacion.class),
                () -> mock(universidad.mensajeria.clienteservidor.InterfazClienteServidor.class),
                mock(InterfazServiciosDisponibles.class),
                mock(InterfazUsuariosDisponibles.class),
                mock(InterfazGestorMensajes.class),
                mock(InterfazMensajes.class),
                eventos,
                mock(InterfazConexionesClientes.class),
                mock(InterfazAlmacenarInformacion.class),
                new LimitesDTO(1, 1, 52_428_800L),
                mock(Casos.class));

        fachada.alRechazoPorSaturacion("10.0.0.5", 1);

        verify(eventos).registrar(eq(TipoAccion.LIMITE_ALCANZADO),
                contains("Pool saturado"), isNull(), eq("10.0.0.5"), isNull());
    }
}
