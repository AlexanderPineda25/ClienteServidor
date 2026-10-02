package universidad.mensajeria.protocolo.impl;

import universidad.mensajeria.protocolo.ConfigRed;
import universidad.mensajeria.protocolo.InterfazProtocoloComunicacion;
import universidad.mensajeria.protocolo.ProveedorProtocoloComunicacion;

/** Registro SPI (PLAN2 §2.4): descubierto por Fabrica con ServiceLoader. */
public final class ProveedorProtocoloComunicacionImpl implements ProveedorProtocoloComunicacion {

    @Override
    public InterfazProtocoloComunicacion crear(ConfigRed config) {
        return new ProtocoloComunicacion(config);
    }
}
