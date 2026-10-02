package universidad.mensajeria.common.codec;

/** Trama malformada o JSON invalido al decodificar. */
public class CodecException extends RuntimeException {
    public CodecException(String mensaje) {
        super(mensaje);
    }
    public CodecException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
