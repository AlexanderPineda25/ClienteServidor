# Despliegue del cliente .NET / WPF

Cliente de escritorio Windows, objetivo `net8.0-windows`, WPF UI y SQLite. En ejecución no requiere
el monorepo, Maven, Python ni conexión directa a MySQL.

## Publicación para un equipo Windows x64

Desde la raíz `universidad-mensajeria/`, en una máquina de compilación Windows con .NET SDK 8 o
posterior:

```powershell
dotnet publish client-dotnet/Mensajeria.csproj `
  -c Release -r win-x64 --self-contained true `
  -o client-dotnet/bin/publish-win-x64
```

La primera publicación restaura paquetes NuGet y el runtime Windows. Copia la carpeta completa
`client-dotnet/bin/publish-win-x64` al PC destino; incluye `Mensajeria.exe`, runtime, dependencias,
`client.properties`, `Store/schema-local.sql` y este README. No copies solo el `.exe` ni `bin/Debug`.

La auditoría NuGet actual de tests advierte gravedad alta en la dependencia transitiva
`SQLitePCLRaw.lib.e_sqlite3 2.1.6` (vía `Microsoft.Data.Sqlite 8.0.10`, fijado a propósito).
Decisión vigente: se mantiene la versión fijada y la alerta queda como riesgo conocido a
revisar antes de un despliegue de producción; no se actualiza sin probar la suite completa.

En el destino, inicia `Mensajeria.exe`. Al ser self-contained no requiere instalar .NET Desktop
Runtime. Para publicar en ARM64 o una plataforma distinta, sustituye el Runtime Identifier (`-r`)
y valida WPF en esa plataforma; WPF es exclusivamente Windows.

## Desarrollo local

Desde la raíz del proyecto:

```powershell
dotnet run --project client-dotnet/Mensajeria.csproj
```

El proyecto de UI compila contra `net8.0-windows`. La suite actual de tests apunta a `net10.0`, por
lo que ejecutarla requiere .NET SDK 10:

```powershell
dotnet test client-dotnet.Tests/Mensajeria.Tests.csproj
dotnet build client-dotnet/Mensajeria.csproj -c Release
```

## Configuración y red

En publicación, `client.properties` queda junto al ejecutable. Modifica ese archivo antes de
iniciar:

```properties
host=192.168.1.20
puerto=5000
db.url=sqlite:///~/mensajeria_cliente.db
db.user=
db.pass=
idioma=es
```

Configura IP LAN/DNS del servidor y el puerto TCP `red.puerto`; `localhost` solo vale en una
instalación local. Permite salida TCP del cliente hacia el servidor. No abras un puerto de entrada
ni configures MySQL en este equipo.

La base SQLite es local al perfil del usuario, con sufijo por cuenta; instancias del mismo usuario
en el mismo PC pueden compartirla. La migración del archivo legacy es aditiva. Las imágenes
descargadas se guardan bajo `%LOCALAPPDATA%\Mensajeria\imagenes`; no elimines esa carpeta si deseas
conservar archivos locales.

## Operación

- El alta de usuarios se realiza en el servidor; la UI no ofrece registro.
- El acceso muestra servidor y puerto (de `client.properties`).
- El directorio trae todos los usuarios como `Nombre Apellido [CÓDIGO]`,
  con filtro Todos/Conectados y buscador. La conversación va en orden
  cronológico (los nuevos abajo) y cada burbuja muestra la fecha abajo
  (`aaaa-MM-dd HH:mm`) junto a Responder.
- Se puede adjuntar con los botones o arrastrando imágenes y archivos
  (hasta 50 MB) sobre la redacción o la lista de mensajes.
- Si no hay conexión, prueba `Test-NetConnection IP_SERVIDOR -Port 5000` antes de reinstalar.
- Actualiza el directorio publicado conservando `client.properties`, SQLite del perfil e imágenes.
- Enviar texto, imagen o ambos no implica que la otra persona ya lo recibió: confirma los estados
  de entrega/lectura en la conversación.
