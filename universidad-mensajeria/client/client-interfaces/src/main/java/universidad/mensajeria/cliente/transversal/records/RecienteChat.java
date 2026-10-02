package universidad.mensajeria.cliente.transversal.records;

/** Fila de la lista de recientes: último mensaje + no leídos + presencia. */
public record RecienteChat(
        String otro,
        String ultimoContenido,
        String ultimoTipo,
        String fechaUltimo,
        int noLeidos,
        boolean conectado) {
}
