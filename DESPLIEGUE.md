# Despliegue por computador

El repositorio es el entorno de construcción. Para instalar el sistema, prepara una distribución por
proceso y lleva solo esa salida al computador que la ejecutará. No instales el monorepo completo en
cada PC.

## Topología y puertos

```text
Cliente JavaFX ─┐
Cliente Python ─┼── TCP/JSON → IP_SERVIDOR:5000 ── Servidor ── MySQL
Cliente WPF ────┘                                  ├── server/uploads/ (monorepo)
                                                   └── server/logs/ (monorepo)
```

| Conexión | Puerto predeterminado | Acceso requerido |
|---|---:|---|
| Clientes → servidor | `5000/TCP` | Entrada en firewall del servidor, limitada a la LAN |
| Servidor → MySQL de Docker de desarrollo | `3307/TCP` host; `3306` contenedor | Solo el equipo servidor |
| Servidor → MySQL instalado | Habitualmente `3306/TCP` | Solo el equipo servidor |
| PostgreSQL/Oracle opcionales | `5432/TCP` / `1521/TCP` | Solo si se habilita ese motor |

Los clientes nunca se conectan a MySQL. En una configuración remota, `localhost` es el mismo PC
del cliente; configura la IP LAN o DNS del equipo servidor. No se usa HTTP/REST.

## Flujo de mensajes

Cada cliente abre una conexión TCP al servidor y envía JSON UTF-8 precedido por una longitud de
4 bytes en orden big-endian. El servidor autentica la sesión, consulta el directorio y la presencia,
y enruta texto, imágenes, historial y acuses de entrega/lectura. El protocolo compartido documenta
los tipos y campos exactos en [PROTOCOLO.md](universidad-mensajeria/common-protocol/PROTOCOLO.md).

MySQL y la carpeta persistente `server/uploads/` pertenecen al servidor. H2/SQLite almacenan el historial
local de cada cliente y no se comparten entre computadores. La presencia es estado de sesión en
vivo del servidor. Para una imagen, el cliente envía sus bytes por TCP; el servidor guarda el
original y produce cinco derivados secuenciales: `01_grises.png`, `02_sepia.png`,
`03_giro180.png`, `04_brillo.png` y `05_reduccion.png`. El historial transmite metadatos, no bytes;
el cliente descarga la imagen bajo demanda con `archivoId`.

## Distribuciones

| PC | Copiar/instalar | Runtime |
|---|---|---|
| Servidor | JAR Spring Boot + configuración + datos persistentes | Java 21 y MySQL accesible |
| Cliente JavaFX | `client-app.jar`, `lib/`, `javafx/`, `client.properties`, launcher | JRE 21 |
| Cliente Python | Carpeta fuente sin `.venv`, con `requirements.txt` y esquema local | Python 3.13 recomendado; crear venv e instalar requirements |
| Cliente .NET | Salida completa de `dotnet publish` | Windows; self-contained incluye runtime .NET |

Las carpetas fuente Java no son todas autónomas: el servidor y JavaFX se construyen desde el
reactor Maven porque usan módulos hermanos. Los README de componentes contienen comandos exactos de
construcción/publicación y copia:

- [Servidor y DBeaver](universidad-mensajeria/server/servidor-app/README.md)
- [Cliente JavaFX](universidad-mensajeria/client/README.md)
- [Cliente Python](universidad-mensajeria/client-python/README.md)
- [Cliente .NET/WPF](universidad-mensajeria/client-dotnet/README.md)

## Orden de instalación

1. Prepara MySQL y el usuario de servicio en el PC servidor. `docker-compose.yml` sirve para
   desarrollo; sus credenciales son públicas y no son aptas para producción.
2. Configura la URL/credenciales de base de datos, `red.puerto` y la ruta persistente de uploads;
   arranca el JAR. Las migraciones Flyway se aplican en el inicio.
3. Permite entrada a `5000/TCP` en el firewall privado del servidor. El servidor usa de forma
   predeterminada el puerto configurable `red.puerto=5000`.
4. Configura `host` y `puerto` en cada `client.properties`; usa IP LAN/DNS, no localhost.
5. Prueba desde cada cliente:

```powershell
Test-NetConnection 192.168.1.20 -Port 5000
```

Solo el servidor debe alcanzar la base de datos. No abras el puerto MySQL a la LAN de clientes.

## Datos y mantenimiento

- MySQL y `server/uploads/` son persistencia autoritativa del servidor. Respalda ambos; la base conserva
  metadatos/rutas, mientras que los bytes de las imágenes están en disco.
- El servidor conserva el original y cinco derivados bajo
  `server/uploads/Nombre Apellido [CODIGO]/<SHA-256>/` en desarrollo. En la distribución aislada,
  la carpeta es `uploads/` junto al JAR.
- Cada cliente guarda H2/SQLite por cuenta local. No distribuyas bases, colas ni imágenes privadas
  dentro del paquete de instalación.
- Al actualizar, reemplaza solo los binarios de programa. Conserva configuración, DBs locales,
  MySQL, uploads e imágenes de usuario.

## Documentación restante

- [Pruebas y aceptación](GUIA_ACEPTACION.md)
- [Requerimientos funcionales](REQUERIMIENTOS.md)
- [Arquitectura](PLAN.md)
- [Estado y backlog](ESTADO_ROADMAP.md)
- [Contrato TCP/JSON](universidad-mensajeria/common-protocol/PROTOCOLO.md)
- [Persistencia local](universidad-mensajeria/common-protocol/HISTORIAL_LOCAL.md)
