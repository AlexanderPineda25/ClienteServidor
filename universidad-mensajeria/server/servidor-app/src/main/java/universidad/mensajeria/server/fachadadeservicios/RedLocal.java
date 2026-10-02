package universidad.mensajeria.server.fachadadeservicios;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/** IP local para el contrato Fachada (antes DetectorIP del comp red). */
final class RedLocal {

    private RedLocal() {
    }

    static String ipLocal() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> direcciones = nic.getInetAddresses();
                while (direcciones.hasMoreElements()) {
                    InetAddress direccion = direcciones.nextElement();
                    if (direccion instanceof Inet4Address && !direccion.isLoopbackAddress()) {
                        return direccion.getHostAddress();
                    }
                }
            }
        } catch (Exception ignorada) {
            // sin red: se devuelve loopback abajo
        }
        return "127.0.0.1";
    }
}
