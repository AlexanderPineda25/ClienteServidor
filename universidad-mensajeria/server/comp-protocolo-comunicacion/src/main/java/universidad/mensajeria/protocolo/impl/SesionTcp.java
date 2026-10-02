package universidad.mensajeria.protocolo.impl;

import universidad.mensajeria.common.codec.TramaCodec;
import universidad.mensajeria.common.interno.IdSesion;
import universidad.mensajeria.common.tipos.Mensaje;

import java.io.IOException;
import java.net.Socket;

/**
 * Socket + identidad (INTERNO del componente, PLAN2 §2.5 Regla 4).
 * Jamas cruza fronteras: afuera solo viaja {@link IdSesion}.
 * Escritura sincronizada por sesion con flush (TramaCodec, §4.3).
 */
final class SesionTcp {

    private final Socket socket;
    private final Object candadoSalida = new Object();
    private volatile IdSesion id;
    private volatile boolean cerrada;

    SesionTcp(Socket socket) {
        this.socket = socket;
        String ip = socket.getInetAddress() != null
                ? socket.getInetAddress().getHostAddress()
                : "0.0.0.0";
        this.id = IdSesion.anonima(ip);
    }

    IdSesion id() {
        return id;
    }

    void autenticar(String codigo) {
        this.id = id.autenticada(codigo);
    }

    boolean activa() {
        return !cerrada && !socket.isClosed();
    }

    Mensaje leer() throws IOException {
        return TramaCodec.leer(socket.getInputStream());
    }

    void enviar(Mensaje mensaje) throws IOException {
        synchronized (candadoSalida) {
            TramaCodec.escribir(socket.getOutputStream(), mensaje);
        }
    }

    void fijarTimeoutLectura(int milisegundos) throws IOException {
        socket.setSoTimeout(milisegundos);
    }

    void cerrar() {
        cerrada = true;
        try {
            socket.close();
        } catch (IOException ignorada) {
            // ya cerrado
        }
    }

    @Override
    public String toString() {
        return "SesionTcp{" + (id.codigo() == null ? "sin-login" : id.codigo())
                + "@" + id.direccionIp() + "}";
    }
}
