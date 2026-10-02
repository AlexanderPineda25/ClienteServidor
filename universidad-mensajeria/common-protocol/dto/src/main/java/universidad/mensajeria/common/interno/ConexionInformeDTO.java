package universidad.mensajeria.common.interno;

/**
 * Fila del Informe 2 (FASE 9, §8): frecuencia de acceso. vecesConectado
 * viene de un COUNT(*) GROUP BY (JPQL) sobre registro_acciones; sesionesVivas
 * del mapa en caliente ConexionesClientes.
 */
public record ConexionInformeDTO(String codigo, long vecesConectado, int sesionesVivas,
                                 String fechaUltimaConexion, String nombres, String apellidos) {
    public ConexionInformeDTO(String codigo, long vecesConectado, int sesionesVivas,
                              String fechaUltimaConexion) {
        this(codigo, vecesConectado, sesionesVivas, fechaUltimaConexion, null, null);
    }
}
