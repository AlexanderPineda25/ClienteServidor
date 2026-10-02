# HISTORIAL_LOCAL — queries canónicas multilenguaje

> Contrato de columnas y semánticas compartido por **todos** los clientes (Java/H2,
> Python/sqlite3, C#/SQLite), sobre `schema-local.sql`. Los adaptadores pueden usar
> la sintaxis de parámetros/upsert nativa de cada motor, pero preservan estos campos,
> estados y reglas de carga diferida (§5.2 del PLAN).

Convenciones:
- `?` representa un valor ligado; cada adaptador puede usar parámetros nombrados.
- Fechas en ISO-8601 **texto** (`TEXT`), nunca tipos temporales nativos.
- Booleans como `INTEGER` 0/1.
- `upsert`: cada adaptador conserva la misma semántica; H2 usa DELETE + INSERT y SQLite puede
  usar `ON CONFLICT DO UPDATE`.

## Aislamiento por cuenta

`historial_local.enviado`, `estado` y `pendientes_envio` describen la perspectiva de una cuenta;
por tanto, los clientes guardan estos datos en un archivo local derivado de la cuenta autenticada.
Varias instancias de la misma cuenta pueden compartir el archivo. Cuentas distintas no comparten
un registro con el mismo `id_mensaje`: cada archivo guarda la conversación correspondiente y
calcula `enviado` como `origen == codigo_de_la_cuenta`.

Al migrar desde una base anterior compartida, copiar una sola vez los mensajes donde la cuenta sea
origen o destino, conservar IDs, blobs, `archivo_id`, ruta y estado disponible, y copiar pendientes
creados por esa cuenta. La importación debe ser transaccional/idempotente y preservar intacto el
archivo legado. No se comparte estado de presencia entre bases locales; la presencia vigente viene
del servidor.

---

## 1. upsertMensaje (recibir o enviar)

```sql
DELETE FROM historial_local WHERE id_mensaje = ?;

INSERT INTO historial_local (
    id_mensaje, origen, destino, tipo, contenido, hash_sha256,
    num_caracteres, num_palabras, nombre_archivo, ruta_archivo,
    tamano_archivo, fecha_envio, enviado, descargado, estado, archivo_id
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
```

Parámetros: `id_mensaje, origen, destino, tipo, contenido, hash_sha256,
num_caracteres, num_palabras, nombre_archivo, ruta_archivo, tamano_archivo,
fecha_envio, enviado(0|1), descargado(0|1), estado, archivo_id`. El estado es
monotónico (`PENDIENTE → ENVIADO → ENTREGADO → LEIDO`); `archivo_id` permite
recuperar imágenes remotas tras reiniciar.

El ejemplo usa `DELETE + INSERT`; SQLite también puede usar `ON CONFLICT DO UPDATE`
si conserva los metadatos existentes y nunca hace retroceder el estado.

## 2. paginaConversacion (historial paginado offline)

```sql
SELECT id_mensaje, origen, destino, tipo,
       CASE WHEN tipo = 'MENSAJE_IMAGEN' THEN NULL ELSE contenido END AS contenido,
       hash_sha256,
       num_caracteres, num_palabras, nombre_archivo, ruta_archivo,
       tamano_archivo, fecha_envio, enviado, descargado, estado, archivo_id
FROM historial_local
WHERE (origen = ? AND destino = ?)    -- conversacion A->B
   OR (origen = ? AND destino = ?)    -- y B->A
ORDER BY fecha_envio DESC
LIMIT ? OFFSET ?;
```

Parámetros: `(remitente, destinatario, destinatario, remitente, tamano, offset)`.
El orden es DESC (lo más reciente primero); los clientes invierten al pintar.

Conteo para `totalPaginas`:

```sql
SELECT COUNT(*)
FROM historial_local
WHERE (origen = ? AND destino = ?)
   OR (origen = ? AND destino = ?);
```

## 3. marcarDescargado

```sql
UPDATE historial_local
SET descargado = 1, ruta_archivo = ?
WHERE id_mensaje = ?;
```

## 4. pendientesEnvio / eliminarPendiente

```sql
SELECT id, tipo, origen, destino, contenido, nombre_archivo, payload,
       fecha_creado, intentos, ultimo_error
FROM pendientes_envio
ORDER BY fecha_creado ASC;
```

```sql
DELETE FROM pendientes_envio WHERE id = ?;
```

## 5. registrarPendiente / incrementarIntento

```sql
DELETE FROM pendientes_envio WHERE id = ?;
INSERT INTO pendientes_envio (id, tipo, origen, destino, contenido,
    nombre_archivo, payload, fecha_creado, intentos, ultimo_error)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, NULL);
```

```sql
UPDATE pendientes_envio
SET intentos = intentos + 1, ultimo_error = ?
WHERE id = ?;
```

## 6. upsertUsuarioCache

```sql
DELETE FROM cache_usuarios WHERE codigo = ?;

INSERT INTO cache_usuarios (codigo, nombres, apellidos, programa,
    conectado, fecha_registro, actualizado)
VALUES (?, ?, ?, ?, ?, ?, ?);
```

## 7. listarCacheUsuarios

```sql
SELECT codigo, nombres, apellidos, programa, conectado, fecha_registro, actualizado
FROM cache_usuarios
ORDER BY conectado DESC, apellidos ASC;
```

## 8. actualizarContenidoDescargado (Fase 13.13)

Guarda los bytes de una imagen descargada bajo demanda (`DESCARGAR_ARCHIVO`).
Portable H2/SQLite (UPDATE por clave, sin sintaxis de motor).

```sql
UPDATE historial_local SET contenido = ? WHERE id_mensaje = ?;
```

## 9. Migraciones, imágenes diferidas y acuses (Fases 15–16)

`estado` y `archivo_id` forman parte del esquema canónico. Los tres clientes los agregan
mediante migraciones locales aditivas e idempotentes, preservando los historiales existentes.
Java conserva H2 y Python/.NET conservan SQLite; esto no cambia el protocolo TCP.

Las páginas de conversación devuelven metadatos y omiten `contenido` para imágenes. Al entrar
una imagen en el área visible, el cliente consulta su ruta de caché o recupera el `archivo_id`
en segundo plano. Las imágenes locales antiguas que aún conservan Base64 se leen por ID solo
en ese momento; las nuevas no cargan blobs durante el refresco.

La cola `pendientes_envio` puede contener acuses con `tipo = 'MENSAJE_LEIDO'`; `contenido`
guarda el ID del mensaje leído y `id` usa un valor estable derivado de ese mensaje. Los tres
clientes conservan los acuses pendientes y los reintentan al reconectar. Un acuse entrante solo
actualiza el estado por ID y nunca genera otro acuse.

---

## Verificación (criterio §13.9)

Fixture compartida en `common-protocol` → las mismas filas insertadas con estas
queries deben devolver resultados idénticos en H2 (Java), sqlite3 (Python) y
Microsoft.Data.Sqlite (C#).
