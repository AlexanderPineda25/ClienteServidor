package universidad.mensajeria.protocolo.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import universidad.mensajeria.common.codec.TramaCodec;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.tipos.Mensaje;
import universidad.mensajeria.common.tipos.TipoMensaje;
import universidad.mensajeria.protocolo.ConfigRed;
import universidad.mensajeria.protocolo.InterfazProtocoloComunicacion;
import universidad.mensajeria.protocolo.ReceptorDeTramas;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.nio.file.Path;
import java.security.cert.X509Certificate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FASE 11.2: doble puerto plano/TLS con el mismo framing. */
class TlsAceptaConexionTest {

    @TempDir
    Path base;

    private static final ReceptorDeTramas NULO = new ReceptorDeTramas() {
        @Override
        public void alConectar(IdSesion sesion) {
        }

        @Override
        public void alRecibir(IdSesion sesion, Mensaje mensaje) {
        }

        @Override
        public void alDesconectar(IdSesion sesion, String motivo) {
        }
    };

    @Test
    void handshakeTlsYTramaConInactividad() throws Exception {
        Path almacen = base.resolve("keystore.p12");
        ProcessBuilder keytool = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin",
                        System.getProperty("os.name").toLowerCase().contains("win")
                                ? "keytool.exe" : "keytool").toString(),
                "-genkeypair", "-alias", "mensajeria", "-keyalg", "RSA", "-keysize", "2048",
                "-storetype", "PKCS12", "-keystore", almacen.toString(),
                "-validity", "30", "-storepass", "test123", "-keypass", "test123",
                "-dname", "CN=localhost");
        keytool.redirectErrorStream(true);
        Process proceso = keytool.start();
        String salida = new String(proceso.getInputStream().readAllBytes());
        assertEquals(0, proceso.waitFor(), salida);

        InterfazProtocoloComunicacion protocolo =
                new ProveedorProtocoloComunicacionImpl().crear(new ConfigRed(0, 2, 4, 10, 1,
                        0, 10, true, 0, almacen.toString(), "test123"));
        protocolo.registrarReceptor(NULO);
        protocolo.iniciar();
        int tlsPuerto = ((ProtocoloComunicacion) protocolo).puertoTls();
        try {
            TrustManager[] sinVerificar = new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] cadena, String auth) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] cadena, String auth) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext contexto = SSLContext.getInstance("TLS");
            contexto.init(null, sinVerificar, null);
            try (SSLSocket cliente = (SSLSocket) contexto.getSocketFactory()
                    .createSocket("127.0.0.1", tlsPuerto)) {
                cliente.setSoTimeout(10000);
                cliente.startHandshake();
                Mensaje aviso = TramaCodec.leer(cliente.getInputStream());
                assertEquals(TipoMensaje.CLOSE_NOTICE, aviso.tipo());
                assertTrue(aviso.mensajeError().contains("inactividad"));
            }
        } finally {
            protocolo.detener();
        }
    }
}
