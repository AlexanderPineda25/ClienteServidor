package universidad.mensajeria.common.tipos;

import java.util.List;

/**
 * DTO neutro del wire protocol. Record JSON: los nombres de campo del JSON
 * coinciden exactamente con los componentes (ver common-protocol/PROTOCOLO.md).
 * Cualquier lenguaje puede producir/consumir este objeto sin Java.
 */
public record Mensaje(
        TipoMensaje tipo,
        String id,
        String fechaHora,
        String codigo,
        String contrasena,
        String nombres,
        String apellidos,
        String programa,
        String remitente,
        String destinatario,
        String contenido,
        String hashSha256,
        Integer numCaracteres,
        Integer numPalabras,
        String etapasFiltrado,
        String nombreArchivo,
        Long tamanoArchivo,
        String archivoId,
        String mime,
        String contenidoImagen,
        Integer pagina,
        Integer totalPaginas,
        Boolean exito,
        String mensajeError,
        String ipRemitente,
        String ipDestinatario,
        List<String> usuariosConectados,
        Integer totalPartes,
        Integer indiceParte
) {

    public static Builder builder() {
        return new Builder();
    }

    /** Fabrica rapida: solo tipo (demas campos en null y no se serializan). */
    public static Mensaje de(TipoMensaje tipo) {
        return builder().tipo(tipo).build();
    }

    public static final class Builder {
        private TipoMensaje tipo;
        private String id;
        private String fechaHora;
        private String codigo;
        private String contrasena;
        private String nombres;
        private String apellidos;
        private String programa;
        private String remitente;
        private String destinatario;
        private String contenido;
        private String hashSha256;
        private Integer numCaracteres;
        private Integer numPalabras;
        private String etapasFiltrado;
        private String nombreArchivo;
        private Long tamanoArchivo;
        private String archivoId;
        private String mime;
        private String contenidoImagen;
        private Integer pagina;
        private Integer totalPaginas;
        private Boolean exito;
        private String mensajeError;
        private String ipRemitente;
        private String ipDestinatario;
        private List<String> usuariosConectados;
        private Integer totalPartes;
        private Integer indiceParte;

        public Builder tipo(TipoMensaje v) { this.tipo = v; return this; }
        public Builder id(String v) { this.id = v; return this; }
        public Builder fechaHora(String v) { this.fechaHora = v; return this; }
        public Builder codigo(String v) { this.codigo = v; return this; }
        public Builder contrasena(String v) { this.contrasena = v; return this; }
        public Builder nombres(String v) { this.nombres = v; return this; }
        public Builder apellidos(String v) { this.apellidos = v; return this; }
        public Builder programa(String v) { this.programa = v; return this; }
        public Builder remitente(String v) { this.remitente = v; return this; }
        public Builder destinatario(String v) { this.destinatario = v; return this; }
        public Builder contenido(String v) { this.contenido = v; return this; }
        public Builder hashSha256(String v) { this.hashSha256 = v; return this; }
        public Builder numCaracteres(Integer v) { this.numCaracteres = v; return this; }
        public Builder numPalabras(Integer v) { this.numPalabras = v; return this; }
        public Builder etapasFiltrado(String v) { this.etapasFiltrado = v; return this; }
        public Builder nombreArchivo(String v) { this.nombreArchivo = v; return this; }
        public Builder tamanoArchivo(Long v) { this.tamanoArchivo = v; return this; }
        public Builder archivoId(String v) { this.archivoId = v; return this; }
        public Builder mime(String v) { this.mime = v; return this; }
        public Builder contenidoImagen(String v) { this.contenidoImagen = v; return this; }
        public Builder pagina(Integer v) { this.pagina = v; return this; }
        public Builder totalPaginas(Integer v) { this.totalPaginas = v; return this; }
        public Builder exito(Boolean v) { this.exito = v; return this; }
        public Builder mensajeError(String v) { this.mensajeError = v; return this; }
        public Builder ipRemitente(String v) { this.ipRemitente = v; return this; }
        public Builder ipDestinatario(String v) { this.ipDestinatario = v; return this; }
        public Builder usuariosConectados(List<String> v) { this.usuariosConectados = v; return this; }
        public Builder totalPartes(Integer v) { this.totalPartes = v; return this; }
        public Builder indiceParte(Integer v) { this.indiceParte = v; return this; }

        public Mensaje build() {
            return new Mensaje(tipo, id, fechaHora, codigo, contrasena, nombres, apellidos,
                    programa, remitente, destinatario, contenido, hashSha256, numCaracteres,
                    numPalabras, etapasFiltrado, nombreArchivo, tamanoArchivo, archivoId, mime,
                    contenidoImagen, pagina, totalPaginas, exito, mensajeError, ipRemitente,
                    ipDestinatario, usuariosConectados, totalPartes, indiceParte);
        }
    }
}
