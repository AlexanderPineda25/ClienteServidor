package universidad.mensajeria.cliente;

import javafx.application.Application;
import universidad.mensajeria.cliente.componentes.autenticacion.Autenticacion;
import universidad.mensajeria.cliente.componentes.cache.CacheUsuarios;
import universidad.mensajeria.cliente.componentes.conexion.ConexionCliente;
import universidad.mensajeria.cliente.componentes.historial.HistorialLocal;
import universidad.mensajeria.cliente.fachada.FachadaCliente;
import universidad.mensajeria.cliente.persistencia.LocalStore;
import universidad.mensajeria.cliente.presentacion.ClienteApp;
import universidad.mensajeria.cliente.transversal.config.ClienteConfig;

/**
 * Arranque del cliente Java principal (§7.1): lee client.properties, crea la
 * base local H2 con schema-local.sql, monta las capas con DI manual
 * (presentacion → fachada → componentes → persistencia) y lanza JavaFX.
 */
public final class ClienteMain {

    public static void main(String[] args) throws Exception {
        ClienteConfig config = ClienteConfig.cargar();

        LocalStore store = new LocalStore(config);
        int sentencias = store.inicializar();
        Runtime.getRuntime().addShutdownHook(new Thread(store::cerrar, "cierre-h2"));

        ConexionCliente red = new ConexionCliente(config.host(), config.puerto(),
                config.tlsHabilitado(), config.tlsPuerto(), config.tlsConfianza());
        HistorialLocal historial = new HistorialLocal(store);
        CacheUsuarios cache = new CacheUsuarios(store);
        Autenticacion autenticacion = new Autenticacion(red);
        FachadaCliente fachada = new FachadaCliente(red, autenticacion, historial, cache);

        System.out.println("=== Cliente Mensajeria (Java) ===");
        // Marcador de build (si no ves esta línea con la fase
        // actual, corres clases viejas: reconstruye con -am).
        System.out.println("build      : Fase 15 (identidad, respuestas e imágenes diferidas)");
        System.out.println("servidor   : " + config.host() + ":" + config.puerto()
                + (config.tlsHabilitado() ? " (TLS :" + config.tlsPuerto() + ")" : ""));
        System.out.println("historial  : " + config.dbUrl() + " (" + sentencias + " DDL)");
        System.out.println("idioma     : " + config.idioma());

        ClienteApp.iniciar(fachada);
        Application.launch(ClienteApp.class, args);
    }

    private ClienteMain() {
    }
}
