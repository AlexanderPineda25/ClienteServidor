package universidad.mensajeria.common.interno;

import java.util.UUID;

/**
 * Identidad opaca de una sesion TCP (PLAN2 §2.5 Regla 4).
 * Lo unico que cruza fronteras de componente: los sockets (SesionCliente,
 * ClienteHandler) son internos de comp-protocolo-comunicacion.
 */
public record IdSesion(String id, String codigo, String direccionIp) {

    public static IdSesion anonima(String direccionIp) {
        return new IdSesion(UUID.randomUUID().toString(), null, direccionIp);
    }

    public IdSesion autenticada(String codigo) {
        return new IdSesion(id, codigo, direccionIp);
    }

    public boolean autenticada() {
        return codigo != null;
    }
}
