# Guía de pruebas y aceptación

Esta guía cubre las comprobaciones compartidas del servidor y los tres clientes. Los pasos para
instalar cada proceso están en [`DESPLIEGUE.md`](DESPLIEGUE.md) y en el README dentro de cada
carpeta de despliegue.

## Pruebas automatizadas

Desde `universidad-mensajeria/`, con Docker disponible y MySQL de pruebas en `localhost:3307`:

```powershell
docker compose up -d mysql
mvn -pl server/servidor-app,client/client-app -am test
```

Pruebas de Python, desde `client-python/`:

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests
```

Pruebas y build WPF, desde `universidad-mensajeria/` (los tests actuales apuntan a .NET 10):

```powershell
dotnet test client-dotnet.Tests/Mensajeria.Tests.csproj
dotnet build client-dotnet/Mensajeria.csproj -c Release
```

La aceptación automatizada no sustituye la prueba manual entre procesos y computadores.

## Preparación del smoke

1. Ejecuta el servidor y cada cliente en computadores separados, o valida primero en LAN con
   ventanas separadas. Asegura que el firewall permite cliente → servidor en `5000/TCP`.
2. Confirma conectividad desde cada cliente con `Test-NetConnection IP_SERVIDOR -Port 5000`.
3. Usa cuentas distintas. El alta de usuarios desde las UIs está desactivada; las cuentas de
   demostración están en el CSV de semilla del servidor.
4. Comprueba primero una dirección Java ↔ cliente remoto y luego todas las parejas de la matriz.

## Matriz de interoperabilidad

En **cada dirección** verifica mensaje recibido una sola vez, remitente/nombre correctos, burbuja
propia a la derecha y recibida a la izquierda, estado entregado/leído sobre el mensaje correcto:

| Pareja | Direcciones que se deben probar |
|---|---|
| Java ↔ Python | Java → Python; Python → Java |
| Java ↔ .NET | Java → .NET; .NET → Java |
| Python ↔ .NET | Python → .NET; .NET → Python |

Para cada dirección envía: texto; imagen sola; texto e imagen; respuesta a texto; respuesta a
imagen. Revisa que una cita llegue como texto ordinario y que un cliente no emita un segundo acuse
al recibir `MENSAJE_LEIDO` o `MENSAJE_ENTREGADO`.

## Historial, sesiones e imágenes

- Mantén dos procesos Java abiertos con cuentas distintas. Un mismo ID recibido debe quedar con la
  orientación relativa a cada cuenta; enviar en una cuenta no debe parecer un mensaje propio en la
  otra.
- Abre dos sesiones del mismo usuario. Cerrar una con `LOGOUT` no debe desconectar la otra; la
  presencia queda offline únicamente después de cerrar la última. Prueba explícitamente «Salir en
  todos».
- Envía una imagen nueva y comprueba en el servidor `uploads/Nombre Apellido [CODIGO]/<hash>/` el
  original y las cinco derivadas. En el otro cliente la imagen aparece en la conversación, también
  después de reiniciar; la carga es diferida, no bloquea la UI, y un fallo permite reintento.
- Cierra y vuelve a abrir cada cliente. Verifica historial, nombre/código, metadatos de imagen y
  acuses. No borres las bases legacy antes de comprobar la migración por cuenta.
- Desconecta temporalmente un cliente, vuelve a conectarlo y comprueba reintento sin duplicados.
- Comprueba que el botón de registro no aparece en ninguna UI.

## Informes y almacenamiento del servidor

- Inicia sesión y consulta `informe conexiones`: el conteo aumenta por login exitoso, no por enviar,
  listar o leer mensajes; las sesiones vivas cambian al abrir/cerrar sesiones.
- En informes 2–4 se muestra `Nombre Apellido [CODIGO]`; el filtro por código sigue funcionando.
- Inspecciona filas, rutas y auditoría con DBeaver siguiendo la sección de la [guía del servidor](universidad-mensajeria/server/servidor-app/README.md#inspección-con-dbeaver).
- La presencia es estado vivo en memoria del servidor; no concluyas que una persona está online
  basándote en una fila histórica de MySQL.

## Registro del resultado

Anota sistema operativo y versiones, commit/versión desplegada, IP y puerto (sin contraseñas),
pareja/dirección, caso probado, resultado, hora e ID del mensaje. Si falla, conserva el log
pertinente de cliente y servidor y revisa el mismo ID y tipo de trama en ambos extremos.
