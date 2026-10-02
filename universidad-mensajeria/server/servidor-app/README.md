# Despliegue del servidor

Guía para construir el servidor en el equipo de desarrollo y ejecutarlo en un equipo servidor
separado. El proceso desplegado necesita Java 21 y una base de datos MySQL; los clientes solo se
conectan al puerto TCP de mensajería.

## Topología y puertos

| Conexión | Puerto predeterminado | Quién lo necesita |
|---|---:|---|
| Clientes → servidor, TCP/JSON | `5000/TCP` (`red.puerto`) | Clientes de la red; permitir entrada en el firewall del servidor |
| Servidor → MySQL de desarrollo Docker | `3307/TCP` en el host (`3306` dentro de Docker) | Solo el proceso servidor y administradores autorizados |
| Servidor → MySQL instalado | normalmente `3306/TCP` | Solo el proceso servidor |
| PostgreSQL / Oracle opcionales | `5432/TCP` / `1521/TCP` | Solo si se configura ese motor |

No configures clientes con el puerto de MySQL. `localhost` significa el mismo computador donde
se ejecuta el proceso: en un cliente remoto se debe usar la IP LAN o el nombre DNS del servidor.
No publiques MySQL hacia toda la LAN o Internet para que los clientes funcionen.

## Requisitos

- Windows Server o Windows 10/11, o un sistema operativo compatible con Java 21.
- JRE 21 en la máquina de ejecución. JDK 21 + Maven 3.9+ únicamente en la máquina de compilación.
- MySQL 8.4 accesible desde el servidor. Docker Compose incluido es para desarrollo/pruebas.
- Puerto TCP del servidor libre; disco persistente para base de datos, logs y archivos subidos.

## Construcción y carpeta independiente

Ejecuta desde `universidad-mensajeria/`, en la máquina de compilación:

```powershell
mvn -pl server/servidor-app -am clean package -DskipTests
$dist = "$env:USERPROFILE\Desktop\MensajeriaServidor"
New-Item -ItemType Directory -Force -Path $dist
Copy-Item server/servidor-app/target/servidor-app-1.0.0-SNAPSHOT.jar "$dist/servidor.jar"
Copy-Item server/servidor-app/README.md "$dist/README.md"
New-Item -ItemType Directory -Force -Path "$dist/uploads", "$dist/logs", "$dist/config"
```

El JAR de Spring Boot incluye los módulos Java del servidor, el protocolo y las dependencias de
ejecución. La carpeta fuente `server/servidor-app` no se compila sola: `-am` construye los módulos
hermanos necesarios. Lleva únicamente la carpeta `$dist` al computador servidor; no es necesario
instalar Maven allí.

Conserva `servidor.jar` y los datos fuera de directorios temporales. Crea `uploads`, `logs` y un
directorio `config` junto al JAR. En el monorepo las rutas por defecto son `server/uploads`,
`server/logs` y `server/work`; en la distribución aislada son carpetas hermanas del JAR. El CSV de
usuarios iniciales de desarrollo ya está dentro del JAR;
para producción define una semilla externa con `app.seed=file:C:/Mensajeria/config/usuarios.csv`.

## Base de datos

El ejemplo de desarrollo incluido en `docker-compose.yml` se levanta desde la raíz del proyecto:

```powershell
docker compose up -d mysql
docker compose ps mysql
```

Ese compose publica MySQL en `3307`, base `mensajeria_db`, usuario `root` y contraseña `12345`.
Son credenciales de desarrollo y no deben usarse en producción. En producción crea una base y un
usuario de servicio con permisos mínimos; guarda copias de seguridad en un lugar protegido.

La aplicación ejecuta las migraciones Flyway al arrancar. El motor predeterminado es MySQL. Si la
base está en otra máquina, configura su dirección desde la máquina del servidor; ningún cliente
necesita credenciales de base de datos.

## Configuración y arranque

Los valores predeterminados están en `src/main/resources/application.properties` y
`server.properties`. Las opciones siguientes se pueden pasar como argumentos Spring Boot y
prevalecen sobre los valores empaquetados:

```powershell
Set-Location $dist
java -jar .\servidor.jar `
  --db.active=mysql `
  --db.mysql.url="jdbc:mysql://127.0.0.1:3307/mensajeria_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Bogota" `
  --db.mysql.user=mensajeria_app `
  --db.mysql.pass="CAMBIAR_POR_SECRETO" `
  --red.puerto=5000 `
  --app.seed="file:C:/Mensajeria/config/usuarios.csv" `
  --app.consola=false `
  --app.escritorio=false
```

Si MySQL está instalado directamente, normalmente se usa el puerto `3306` en la URL. Para un
servidor operado manualmente, cambia `--app.consola` o `--app.escritorio` a `true` según necesites
la consola interactiva o la vista JavaFX. Para ejecutar como servicio sin sesión de escritorio,
mantén ambas en `false`.

`red.sesion.timeout-segundos` controla el cierre por inactividad (predeterminado `600` s).
La via `REGISTRO` por red esta eliminada; los usuarios se aprovisionan solo por
el archivo de semilla CSV. El listener TCP acepta el puerto `red.puerto`; no hay HTTP ni REST.

## Firewall y comprobación desde otro computador

En Windows Server, abre una terminal elevada y permite solo la red privada local:

```powershell
New-NetFirewallRule -DisplayName "Mensajeria TCP 5000" -Direction Inbound `
  -Protocol TCP -LocalPort 5000 -RemoteAddress LocalSubnet -Profile Private -Action Allow
```

Desde un cliente, prueba antes de abrir la UI:

```powershell
Test-NetConnection 192.168.1.20 -Port 5000
```

Sustituye la IP por la dirección LAN real del servidor. `TcpTestSucceeded` debe ser `True`. Si no,
revisa IP, firewall, puerto configurado y que el proceso esté iniciado. Solo el equipo servidor
debe tener acceso a MySQL.

## Operación, persistencia y copias

- Los logs se escriben en `server/logs/server.log` dentro del monorepo, o en `logs/server.log`
  junto al JAR desplegado. `-Dlogging.file.name=RUTA` permite fijar una ubicación personalizada.
- Los originales y cinco derivados de imágenes quedan bajo `red.upload-dir`, organizados por
  `Nombre Apellido [CODIGO]/SHA-256/`. Respaldar solo MySQL no respalda los bytes de imagen.
- Las rutas de archivo almacenadas en MySQL son relativas a `red.upload-dir`. Al restaurar o mover
  la base, conserva el árbol completo de uploads asociado; no hace falta migrar el esquema.
- Respaldar coordinadamente MySQL y `server/uploads` (o `uploads` junto al JAR); restringir acceso
  a ambos.
- Cerrar el proceso con `Ctrl+C` en consola o con el mecanismo de parada del servicio. No borrar
  `uploads` para “limpiar” una sesión: los metadatos e historiales pueden seguir referenciándolos.
- La presencia viva se conserva en memoria y refleja sesiones conectadas, no una columna que pueda
  inspeccionarse con una consulta aislada.

## Funcionamiento

El servidor acepta sockets TCP y procesa una trama por mensaje: longitud de 4 bytes big-endian
seguida de JSON UTF-8. No ofrece endpoints HTTP ni requiere que los clientes conozcan MySQL. La
especificación completa, los tipos de mensaje y los límites están en
[PROTOCOLO.md](../../common-protocol/PROTOCOLO.md).

Al iniciar sesión, el servidor registra la sesión en memoria, consulta el directorio de usuarios y
notifica cambios de presencia. Los mensajes de texto se persisten y se enrutan al destinatario; si
no está conectado, quedan disponibles para sincronización posterior. Los acuses identifican el
mensaje original. `LOGOUT` cierra solo el socket que lo solicita; la presencia cambia a desconectado
cuando termina la última sesión de esa cuenta.

Para imágenes, valida la carga, calcula SHA-256, conserva el original y ejecuta los filtros en este
orden: `01_grises.png`, `02_sepia.png`, `03_giro180.png`, `04_brillo.png` y `05_reduccion.png`.
Los archivos quedan bajo `uploads/Nombre Apellido [CODIGO]/<SHA-256>/`; MySQL guarda los metadatos
y el identificador de archivo. El historial entrega ese identificador sin incluir bytes; el cliente
solicita la descarga por TCP cuando necesita mostrar la imagen.

Los archivos genéricos llegan fragmentados (`ARCHIVO_INICIO/PARTE/FIN`, partes de 1 MiB,
tope 50 MB) y se reensamblan con verificación SHA-256; al completar el `FIN` el servidor
responde `ACK` (antes no respondía y el remitente quedaba en espera). La cuenta `SERVIDOR`
(`Sistema Mensajeria`, sin inicio de sesión) es el buzón del sistema: recibe los mensajes
que los usuarios le envían y firma la difusión administrativa.

La difusión administrativa (`Difundir` en consola o vista de escritorio) llega a todos los
usuarios registrados, no solo a los conectados: persiste una copia por usuario y entrega en
vivo a las sesiones activas; quien estaba desconectado la recibe al reconectar. La vista de
escritorio ofrece las pestañas Estado (con indicadores), Conectados, Usuarios, Mensajes
(detalle con contenido, imagen y previsualización de filtros), Informes (con exportar CSV),
Logs en vivo, Pool y Difundir.

## Inspección con DBeaver

Para el MySQL de desarrollo, configura driver MySQL, host `localhost`, puerto `3307`, base
`mensajeria_db`, usuario `root`, contraseña `12345`. En producción usa la dirección y credenciales
del DBA, preferentemente una cuenta de solo lectura. Las consultas son de inspección: no cambies
filas para reparar el estado de un chat.

```sql
SELECT codigo, nombres, apellidos, activo, fecha_ultima_conexion
FROM usuarios ORDER BY apellidos, nombres;

SELECT m.id, m.fecha_envio, m.tipo,
       CONCAT(r.nombres, ' ', r.apellidos, ' [', r.codigo, ']') AS remitente,
       CONCAT(d.nombres, ' ', d.apellidos, ' [', d.codigo, ']') AS destinatario,
       m.hash_sha256, m.archivo_id
FROM mensajes m
JOIN usuarios r ON r.id = m.remitente_id
JOIN usuarios d ON d.id = m.destinatario_id
ORDER BY m.fecha_envio DESC LIMIT 100;

SELECT a.id, a.nombre, a.hash_sha256, a.ruta,
       CONCAT(u.nombres, ' ', u.apellidos, ' [', u.codigo, ']') AS propietario
FROM archivos a JOIN usuarios u ON u.id = a.propietario_id
ORDER BY a.fecha_subida DESC LIMIT 100;

SELECT u.codigo, u.nombres, u.apellidos,
       SUM(CASE WHEN a.tipo = 'LOGIN' THEN 1 ELSE 0 END) AS logins_exitosos
FROM usuarios u LEFT JOIN registro_acciones a ON a.usuario_id = u.id
GROUP BY u.id, u.codigo, u.nombres, u.apellidos
ORDER BY logins_exitosos DESC;
```

Mensajes, listados y lecturas no incrementan accesos; solo `LOGIN` exitoso. `conectados` en la
consola y el Informe 2 son la fuente para sesiones vivas. Las tablas `sesiones_activas` y
`conexiones` no son la autoridad de presencia del flujo actual. Flyway registra la versión del
esquema en `flyway_schema_history`.
