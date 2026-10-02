package universidad.mensajeria.common.interno;

import java.util.List;

/** Pagina sin Spring Data: contenido + indices. */
public record PaginaDTO<T>(
        List<T> contenido,
        int pagina,
        int totalPaginas
) {
}
