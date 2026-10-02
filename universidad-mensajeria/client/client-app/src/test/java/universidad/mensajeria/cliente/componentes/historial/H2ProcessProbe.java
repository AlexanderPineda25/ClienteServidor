package universidad.mensajeria.cliente.componentes.historial;

import universidad.mensajeria.cliente.persistencia.LocalStore;
import universidad.mensajeria.cliente.transversal.config.ClienteConfig;

import java.util.List;
import java.util.Properties;

/** Child-JVM probe used to verify H2 AUTO_SERVER across processes. */
public final class H2ProcessProbe {

    private H2ProcessProbe() {
    }

    public static void main(String[] args) throws Exception {
        Properties propiedades = new Properties();
        propiedades.setProperty("db.url", args[0]);
        LocalStore store = new LocalStore(ClienteConfig.desde(propiedades));
        try {
            store.inicializar();
            HistorialLocal historial = new HistorialLocal(store);
            List<String> ids = historial.pagina("A", "B", 10, 0).stream()
                    .map(mensaje -> mensaje.idMensaje()).toList();
            if (!ids.contains("padre-1")) {
                throw new IllegalStateException("la JVM hija no leyó la fila del proceso padre");
            }
            historial.guardar(HistorialMultiinstanciaTest.mensaje(
                    "hija-1", "escrito por la JVM hija"));
        } finally {
            store.cerrar();
        }
    }
}
