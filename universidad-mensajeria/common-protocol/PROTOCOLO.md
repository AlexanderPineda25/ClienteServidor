# PROTOCOLO — wire protocol multilenguaje (TCP + JSON)

> Contrato único para **servidor Java**, **cliente Java**, **cliente Python** y
> **cliente C#**. Referencia de implementación Java:
> `common-protocol/.../codec/TramaCodec.java`. Si tu lenguaje no está aquí,
> replica este formato y podrás hablar con el servidor (§4.0 del PLAN).

---

## 1. Trama (frame)

```
+--------------------+---------------------------+
| 4 bytes longitud   | payload (N bytes)         |
| entero big-endian   | UTF-8 JSON                |
+--------------------+---------------------------+
```

- `longitud` = byte length del payload (big-endian, dos complemento 32 bits).
- Un mensaje = **exactamente una trama**. No hay marcas fuera de banda.
- **Máximo por trama: 16 MiB** (`TramaCodec.TAMANO_MAXIMO_TRAMA`); superarlo es error fatal y se cierra la conexión.
- El servidor escribe la respuesta como trama idéntica; el cliente bloquea hasta leerla completa.
- Cada socket se serializa en el emisor (`write` atómico por trama); el lector usa `readFully`.

## 2. Payload JSON

Todo payload es un objeto JSON UTF-8 con **`tipo` obligatorio** (catálogo §3).
Los campos `null` **no se emiten**. Nombres de campo en minúscula-camelCase
iguales en los 3 lenguajes.

| Campo | Tipo | Cuándo |
|---|---|---|
| `tipo` | string (§3) | **siempre** |
| `id` | string (uuid v4) | correlación solicitud↔respuesta |
| `fechaHora` | string ISO-8601 local | fecha del evento |
| `codigo`, `contrasena`, `nombres`, `apellidos`, `programa` | string | REGISTRO/LOGIN |
| `remitente`, `destinatario` | string (código usuario) | mensajes |
| `contenido` | string | texto |
| `hashSha256` | string (64 hex) | servidor calcula en pipeline |
| `numCaracteres`, `numPalabras` | int | servidor (texto) |
| `etapasFiltrado` | string (JSON array) | servidor; p.ej. `[{"etapa":"grises","ms":12,"ruta":"..."}]` |
| `nombreArchivo`, `mime` | string | archivos |
| `tamanoArchivo` | long (bytes) | archivos |
| `archivoId` | string | id para `DESCARGAR_ARCHIVO` |
| `contenidoImagen` | string base64 | imágenes ≤ 10 MiB y bloques de archivo ≤1 MiB crudos (o usar descarga) |
| `pagina`, `totalPaginas` | int | `HISTORIAL_PAGE` |
| `exito` | boolean | respuestas |
| `mensajeError` | string | `ERROR` / respuestas fallidas |
| `ipRemitente`, `ipDestinatario` | string | informativo servidor→cliente |
| `usuariosConectados` | array de strings | `LISTAR_CONECTADOS_RESPUESTA` |

## 3. Catálogo de tipos

| Tipo | Dir. | Descripción | Respuesta esperada |
|---|---|---|---|
| `LOGIN` | C→S | `{codigo, contrasena}` | `LOGIN_RESPUESTA` + `SYNC_LOGIN` |
| `LOGIN_RESPUESTA` | S→C | `{exito, mensajeError, ipRemitente}` | — |
| `REGISTRO` | — | **Eliminado**: el alta es solo por archivo plano CSV. Si llega por red responde `ERROR {mensajeError:"tipo no soportado: REGISTRO"}` | — |
| `REGISTRO_RESPUESTA` | — | Reservado histórico (ya no se emite) | — |
| `LOGOUT` | C→S | cierra únicamente el socket/sesión solicitante; la presencia queda online mientras haya otra sesión activa del mismo código | `CLOSE_NOTICE` solo al socket solicitante |
| `KICK` | C→S | `{codigo}` cierra las demás sesiones del propio código | `CLOSE_NOTICE` a las expulsadas + `ACK` al solicitante |
| `MENSAJE_TEXTO` | C→S | `{remitente, destinatario, contenido}` (remitente debe ser el código logueado) | `ACK` + entrega destinatario |
| `MENSAJE_IMAGEN` | C→S | `{remitente, destinatario, nombreArchivo, mime, contenidoImagen}` | `IMAGE_FILTERED_RESULT` + entrega destinatario |
| `MENSAJE_ARCHIVO` | C→S | `{remitente, destinatario, nombreArchivo, mime, contenidoImagen}` (PDF, documentos; solo SHA-256, sin transformar; solo para archivos cuya trama quepa en 16 MiB) | `ACK` + entrega destinatario |
| `ARCHIVO_INICIO` | C→S | `{id: idTransferencia, remitente, destinatario, nombreArchivo, mime, tamanoArchivo, totalPartes}` anuncia una transferencia fragmentada (tope propio 50 MB) | `ACK` |
| `ARCHIVO_PARTE` | C→S | `{id: idTransferencia, archivoId: idTransferencia, indiceParte, contenidoImagen}` un bloque base64 ≤1 MiB; las partes viajan **secuenciales** (una en vuelo) | `ACK` por parte |
| `ARCHIVO_FIN` | C→S | `{id: idTransferencia, archivoId: idTransferencia, hashSha256}` cierra; el servidor verifica partes completas + sha256 y encamina al pipeline como `MENSAJE_ARCHIVO` | `ACK` final |
| `IMAGE_FILTERED_RESULT` | S→C | `{hashSha256, etapasFiltrado[5], archivoId}` | — |
| `BROADCAST` | C→S | `{remitente, contenido}` → entrega a **todas** las sesiones conectadas salvo la emisora | `ACK` al remitente |
| `LISTAR_CONECTADOS` | C→S | — | `LISTAR_CONECTADOS_RESPUESTA` |
| `LISTAR_CONECTADOS_RESPUESTA` | S→C | `{usuariosConectados: [...]}` | — |
| `LISTAR_USUARIOS` | C→S | — | `LISTAR_USUARIOS_RESPUESTA` `{usuarios: [{codigo, nombres, apellidos, programa, conectado}]}` |
| `HISTORIAL_REQ` | C→S | `{remitente, destinatario, pagina}` (pág. tamaño 50) | `HISTORIAL_PAGE` |
| `HISTORIAL_PAGE` | S→C | `{pagina, totalPaginas}` + mensajes **sin bytes** | — |
| `DESCARGAR_ARCHIVO` | C→S | `{archivoId}` | `DESCARGAR_ARCHIVO_RESPUESTA` |
| `DESCARGAR_ARCHIVO_RESPUESTA` | S→C | `{nombreArchivo, mime, contenidoImagen}` | — |
| `SYNC_LOGIN` | S→C | mensajes pendientes desde la última conexión | — |
| `MENSAJE_ENTREGADO` | S→C | `{remitente: destino original, destinatario: autor, contenido: id original}` avisa que el mensaje llegó al menos a una sesión del destinatario (o quedó guardado offline) | — |
| `MENSAJE_LEIDO` | C→S→C | `{remitente: lector, destinatario: autor, contenido: id original}` el lector abrió el chat; el servidor lo reenvía al autor | — |
| `PRESENCIA` | S→C | `{codigo, contenido: "conectado"/"desconectado", usuariosConectados: [...]}` broadcast ante login/logout/cierre | — |
| `ACK` | S→C | `{exito, id}` | — |
| `ERROR` | S→C | `{mensajeError}` | — |
| `CLOSE_NOTICE` | S→C | cierre/kick de sesión | — |

Reglas de correlación:
- Si el cliente envía `id`, la respuesta repite ese `id`.
- `MENSAJE_*` sin `destinatario` válido → `ERROR` con `mensajeError`.
- Un servidor **no sabe** el lenguaje del peer: solo valida tramas.

## 3bis. TLS opcional (FASE 11.2)

- El framing **no cambia** bajo TLS: solo cambia el socket. Doble puerto para no
  romper peers sin TLS (Python sigue en plano hasta migrar): `red.puerto` plano +
  `red.tls.puerto` (defecto `5001`).
- Servidor: `red.tls.habilitado=true` + `red.tls.almacen` (PKCS12) + `red.tls.clave`.
  Generar desarrollo: `keytool -genkeypair -alias mensajeria -keyalg RSA -keysize 2048
  -storetype PKCS12 -keystore config/keystore.p12 -validity 825 -storepass CAMBIAR
  -keypass CAMBIAR -dname "CN=servidor-mensajeria"`. Nunca versionar el almacén real.
- Java: `tls.habilitado=true` + `tls.puerto` + `tls.confianza` (JKS con el cert;
  vacío = truststore de la JVM).
- C#: `tls.habilitado=true` + `tls.puerto` en `client.properties` (cadena válida
  por defecto; `ConexionRed.ValidarCertificado` permite aceptar un autofirmado).
- Python (pendiente Antigravity): `ssl.SSLContext` + `wrap_socket` contra
  `tls.puerto`, mismo framing después del handshake.
- Cierre administrativo (`cerrar <codigo|id-sesion|*>` en consola, pestaña
  Conectados en escritorio) es operación **local** del servidor: no usa trama
  nueva; avisa `CLOSE_NOTICE` y audita `CIERRE_CONEXION`.

## 4. Ejemplos

**LOGIN (cliente → servidor)**

```json
{"tipo":"LOGIN","id":"5f1c...","codigo":"A001234567","contrasena":"Secreta123"}
```

**MENSAJE_TEXTO (cliente → servidor)**

```json
{"tipo":"MENSAJE_TEXTO","id":"9a2b...","remitente":"A001234567","destinatario":"B002345678",
 "contenido":"Hola, ¿alguna duda de Algoritmos?"}
```

**ACK (servidor → remitente) + entrega al destinatario (mismo payload, otro tipo)**

```json
{"tipo":"ACK","id":"9a2b...","exito":true}
```

**IMAGE_FILTERED_RESULT (servidor → remitente)**

```json
{"tipo":"IMAGE_FILTERED_RESULT","id":"7c3d...","hashSha256":"a1b2...","archivoId":"f-0001",
 "etapasFiltrado":"[{\"etapa\":\"grises\",\"ms\":9,\"ruta\":\"uploads/a1b2/01_grises.png\"},{\"etapa\":\"sepia\",\"ms\":11,\"ruta\":\"uploads/a1b2/02_sepia.png\"}]"}
```

## 5. Imágenes y archivos fragmentados

- Envío de imágenes: bytes → **base64** en `contenidoImagen` (≤ 10 MiB; verificar `server.properties`).
- El servidor calcula `hashSha256`, aplica los 5 filtros **secuenciales** y responde
  `IMAGE_FILTERED_RESULT`. Los originales/derivados viven en `uploads/` del servidor.
- Descarga: `DESCARGAR_ARCHIVO {archivoId}` → devuelve base64 bajo demanda
  (el historial NUNCA trae `contenidoImagen`); si el archivo supera 1 MiB, el
  servidor responde con la secuencia `ARCHIVO_INICIO` + `ARCHIVO_PARTE`* +
  `ARCHIVO_FIN` correlacionada por `id` (igual que una subida).
- Archivos genéricos chicos caben en `MENSAJE_ARCHIVO` de una sola trama.
- Archivos hasta **50 MB** (tope propio; efectivo = menor entre
  `configuracion_limites` y `archivo.max-mb` de `server.properties`) usan la
  secuencia fragmentada: el cliente genera `idTransferencia` (uuid), envía
  `ARCHIVO_INICIO`, luego cada parte **secuencial** esperando su `ACK`, y cierra
  con `ARCHIVO_FIN {hashSha256}`. Bloque crudo por parte: **1 MiB**
  (`FragmentoArchivoCaso.PARTE_BYTES`; el base64 resultante cabe en la trama
  16 MiB, que **no** se amplía). El reintento offline reutiliza el mismo
  `idTransferencia` (el servidor reinicia el buffer ante el mismo id).
- Errores: `tamano`/`totalPartes` incoherentes, parte duplicada/fuera de rango,
  base64 inválido, transferencia desconocida/incompleta o sha256 distinto →
  `ERROR` sin encaminar al pipeline.

## 6. Errores

| Situación | Respuesta |
|---|---|
| Trama con longitud inválida o JSON sin `tipo` | cierre de conexión + `ERROR` si es posible |
| Credenciales inválidas | `LOGIN_RESPUESTA {exito:false, mensajeError:"..."}` |
| Comando desconocido | `ERROR {mensajeError:"tipo no soportado: X"}` |
| Operación sin `LOGIN` previo | `ERROR {mensajeError:"autenticate primero con LOGIN"}` |
| `KICK` de otro código | `ERROR {mensajeError:"solo puedes cerrar tus propias sesiones"}` |
| Límite de conexiones alcanzado | `LOGIN_RESPUESTA {exito:false}` + `CLOSE_NOTICE` |
| Inactividad > `red.sesion.timeout-segundos` | `CLOSE_NOTICE {mensajeError:"cierre por inactividad"}` + cierre |
| Corte abrupto de red | se libera solo la sesión afectada; las demás sesiones del mismo código siguen activas. La presencia cambia a desconectado al liberar la última sesión |

## 7. Implementaciones mínimas (semilla por lenguaje)

**Java** — usar `TramaCodec` (este módulo):

```java
TramaCodec.escribir(socket.getOutputStream(), Mensaje.builder().tipo(TipoMensaje.LOGIN)...build());
Mensaje resp = TramaCodec.leer(socket.getInputStream());
```

**Python (stdlib)**

```python
def enviar(sock, obj):
    payload = json.dumps(obj, ensure_ascii=False).encode("utf-8")
    sock.sendall(len(payload).to_bytes(4, "big") + payload)

def recibir(sock):
    n = int.from_bytes(sock.recv(4), "big")
    data = b""
    while len(data) < n:
        data += sock.recv(n - len(data))
    return json.loads(data.decode("utf-8"))
```

**C# (.NET)**

```csharp
byte[] payload = JsonSerializer.SerializeToUtf8Bytes(objo);
byte[] trama = new byte[4 + payload.Length];
BitConverter.TryWriteBytes(trama.AsSpan(0), payload.Length); // BigEndian explicito al invertir si hace falta
Buffer.BlockCopy(payload, 0, trama, 4, payload.Length);
stream.Write(trama);
```

## 8. Versionado

- Cambios compatibles (campo nuevo opcional): sin versión.
- Cambio de framing/renombrado de `tipo`: nueva versión documentada aquí + bump de `PROTOCOLO.md` (§12 fase final).

---

**Verificación cruzada**: criterio §13.8 — conversación a 3 bandas
Java ↔ Python ↔ C# sobre este documento.
