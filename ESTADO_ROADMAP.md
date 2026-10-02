# Estado y roadmap

Actualizado: **2026-10-02**. Este es el tablero breve de estado; la arquitectura está en
`PLAN.md`, los requisitos en `REQUERIMIENTOS.md` y las instrucciones para instalar cada proceso en
`DESPLIEGUE.md`.

## Estado actual

La Fase 17 está implementada en las tres pistas. Java, Python y .NET mantienen toolkits propios y
comparten el contrato TCP. Las pruebas automatizadas recientes en este entorno:

| Componente | Verificación | Resultado |
|---|---|---|
| JavaFX | `mvn -pl client/client-app -am test` | 53 pruebas correctas |
| Servidor + JavaFX | `mvn -pl server/servidor-app,client/client-app -am test` | BUILD SUCCESS; 69 servidor-app + 53 cliente |
| Python | `.venv/Scripts/python.exe -m unittest discover -s tests` | 42 correctas |
| .NET | `dotnet test client-dotnet.Tests/Mensajeria.Tests.csproj` | 34 correctas (app `net8.0-windows`, tests `net10.0`) |
| WPF | `dotnet build client-dotnet/Mensajeria.csproj -c Release` | BUILD SUCCESS |
| WPF distribuible | `dotnet publish ... -r win-x64 --self-contained true` | publicación correcta; incluye exe, propiedades y esquema |

**Fase 18 cerrada el 2026-10-02:** textos en `uploads/<prop>/textos/<hash>/`, archivos
genéricos en carpeta por propietario, auto-refresh de informes (consola + escritorio), etiquetas
`[INFO]/[WARN]/[ERROR]` sin ANSI, auditoría de saturación (`LIMITE_ALCANZADO`) y vía `REGISTRO`
por red eliminada (responde `ERROR`). Suites en verde tras recompilación completa desde fuentes.

Las pruebas automáticas no equivalen al smoke final. Queda por registrar una prueba en equipos
separados que recorra mensajes, imágenes y vistos en las seis direcciones Java↔Python↔.NET, además
de dos cuentas Java, dos instancias del mismo usuario y reconexión. La [guía de aceptación](GUIA_ACEPTACION.md)
contiene la matriz.

`dotnet test` reporta una alerta NuGet de gravedad alta para la dependencia transitiva
`SQLitePCLRaw.lib.e_sqlite3 2.1.6` (vía `Microsoft.Data.Sqlite 8.0.10`, fijado a propósito).
Decisión: se mantiene la versión y queda como riesgo conocido a revisar antes de producción.

## Hechos de despliegue

- Servidor: JAR Spring Boot ejecutable, Java 21, MySQL y directorios persistentes `server/uploads/`
  y `server/logs/` en el monorepo; al desplegar, `uploads/` y `logs/` quedan junto al JAR.
- Cliente JavaFX: carpeta de distribución con el JAR de app, librerías Maven, módulos JavaFX nativos,
  launcher y `client.properties`; no se distribuye solo el submódulo fuente.
- Python: carpeta independiente con `requirements.txt` y esquema SQLite incluido; cada PC crea su
  propio entorno virtual.
- .NET: salida WPF de `dotnet publish`, preferentemente self-contained para Windows x64.
- Clientes → servidor: TCP configurable, predeterminado `5000`. Solo el servidor → MySQL necesita
  conectividad al puerto de base de datos. Ver [DESPLIEGUE.md](DESPLIEGUE.md).

## Trabajo futuro

| Prioridad | Trabajo |
|---|---|
| Antes de dar por aceptada la paridad | Smoke manual integrado de seis direcciones y dos instancias Java; registrar SO/versión/resultado |
| Seguridad | TLS, límites de frecuencia por sesión, validación de magic bytes y política de secretos |
| Distribución (primero) | Contenedor del servidor e instaladores nativos Java/Python; proceso de actualización/backup |
| Rendimiento | Prueba de carga con 50–100 sesiones y métricas de filtros/BD/pool |
| Producto | Notificación al minimizar y salas temáticas, si se priorizan |

Hecho en Fase 18: chunking/progreso de archivos hasta 50 MB (`ARCHIVO_INICIO/PARTE/FIN`),
cierre administrativo (`cerrar`), cero-residuos de tests (`@TempDir` + `logback-test.xml`).

## Índice documental

- [Mapa de despliegue](DESPLIEGUE.md)
- [Guía de aceptación](GUIA_ACEPTACION.md)
- [Servidor y DBeaver](universidad-mensajeria/server/servidor-app/README.md)
- [JavaFX](universidad-mensajeria/client/README.md)
- [Python](universidad-mensajeria/client-python/README.md)
- [.NET/WPF](universidad-mensajeria/client-dotnet/README.md)
- [Contrato TCP/JSON](universidad-mensajeria/common-protocol/PROTOCOLO.md)
- [Contrato de persistencia local](universidad-mensajeria/common-protocol/HISTORIAL_LOCAL.md)
