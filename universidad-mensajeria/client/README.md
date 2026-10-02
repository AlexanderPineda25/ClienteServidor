# Despliegue del cliente JavaFX

JavaFX es el cliente principal. En ejecución requiere solo el JRE Java 21, la carpeta de
distribución JavaFX y conectividad TCP hacia el servidor. No requiere Maven ni acceso a MySQL en el
computador del usuario.

## Construir en desarrollo

Desde la raíz `universidad-mensajeria/`:

```powershell
mvn -pl client/client-app -am install -DskipTests
mvn -pl client/client-app javafx:run
```

El comando usa el reactor Maven y los módulos `client-interfaces`, `dto` y `codec`. Por eso
`client/` como carpeta de fuentes no se copia sola a otra máquina. JavaFX 21 necesita JDK 21 para
compilar; se recomienda también JRE 21 en la máquina final.

## Crear carpeta distribuible para Windows x64

Ejecuta desde la raíz `universidad-mensajeria/`. El destino debe ser una carpeta nueva:

```powershell
$dist = "$env:USERPROFILE\Desktop\MensajeriaClienteJava"
New-Item -ItemType Directory -Path $dist
mvn -pl client/client-app -am install -DskipTests
mvn -pl client/client-app dependency:copy-dependencies -DincludeScope=runtime `
  "-DoutputDirectory=$dist\lib"
Copy-Item client/client-app/target/client-app-1.0.0-SNAPSHOT.jar "$dist/client-app.jar"
Copy-Item client/client-app/src/main/resources/client.properties "$dist/client.properties"
Copy-Item client/launch-client.cmd "$dist/launch-client.cmd"
New-Item -ItemType Directory -Path "$dist/javafx"
Copy-Item "$dist/lib/javafx-*-win.jar" "$dist/javafx/"
Copy-Item client/README.md "$dist/README.md"
```

El primer comando Maven instala los módulos locales requeridos en el repositorio Maven del
constructor; el segundo copia las dependencias de ejecución a `lib`. La carpeta final contiene
JAR de la aplicación, DTO/codec/interfaces, librerías y binarios nativos JavaFX para Windows.
Para otro sistema operativo, genera la distribución en ese mismo sistema y cambia el clasificador
JavaFX del launcher según la plataforma; no reutilices los binarios nativos de Windows.

En el computador destino instala Java 21, copia únicamente la carpeta completa de distribución,
edita `client.properties` y ejecuta `launch-client.cmd`. No copies solo `client-app.jar`.

## Configuración de conexión

El launcher fija como directorio de trabajo la carpeta de distribución. `ClienteConfig` carga los
valores empaquetados y los sobreescribe con el `client.properties` externo si está presente.

```properties
host=192.168.1.20
puerto=5000
db.url=jdbc:h2:~/mensajeria_cliente;AUTO_SERVER=TRUE
db.user=sa
db.pass=
idioma=es
```

Usa la IP LAN o DNS del equipo servidor, no `localhost`, cuando Java se ejecuta en otro PC. Cambia
`puerto` si el servidor usa otro `red.puerto`. También se puede indicar el archivo explícitamente:
`java -Dmensajeria.config=C:/Mensajeria/client.properties ...`; normalmente basta editar el archivo
junto al launcher.

El historial y la cola son locales H2, en un archivo derivado por código de usuario bajo el perfil
local. Cuentas diferentes quedan aisladas; varias instancias del mismo código comparten su archivo.
Los archivos H2 no se copian a otras máquinas para sincronizar mensajes: el servidor conserva el
historial remoto.

## Red y solución de problemas

- Permitir salida TCP desde este equipo a `IP_SERVIDOR:5000`; no abrir puertos entrantes del cliente.
- No configurar MySQL en Java. El puerto de base de datos solo lo usa el proceso servidor.
- `Connection refused` normalmente indica IP/puerto incorrectos o servidor apagado. Verifica
  primero `Test-NetConnection IP_SERVIDOR -Port 5000` en PowerShell.
- Si se actualiza la app, reemplaza `client-app.jar`, `lib/` y `javafx/` como una unidad. Mantén el
  `client.properties` y los archivos H2 locales del usuario.
- Registro no está disponible en la UI; solicita credenciales al administrador.
