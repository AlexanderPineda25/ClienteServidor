package universidad.mensajeria.protocolo;

/**
 * SPI del componente (PLAN2 §2.4): la implementacion se registra en
 * META-INF/services y Fabrica la descubre con ServiceLoader.
 */
public interface ProveedorProtocoloComunicacion {

    InterfazProtocoloComunicacion crear(ConfigRed config);
}
