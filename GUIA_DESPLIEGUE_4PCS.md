# Probar la app en 4 computadores (uno por proceso)

## Supuestos

- Los 4 son Windows 10/11 en la misma LAN.
- Compilas todo en tu PC actual.
- Cada máquina recibe **solo su carpeta** — nada del monorepo.

## Paso 0 — En tu PC (construcción, una sola vez)

```powershell
cd "C:\...\universidad-mensajeria"
mvn -pl server/servidor-app -am clean package -DskipTests
mvn -pl client/client-app -am install -DskipTests
mvn -pl client/client-app dependency:copy-dependencies -DincludeScope=runtime "-DoutputDirectory=$env:USERPROFILE\Desktop\MensajeriaClienteJava\lib"
Copy-Item client/client-app/target/client-app-1.0.0-SNAPSHOT.jar "$env:USERPROFILE\Desktop\MensajeriaClienteJava\client-app.jar"
dotnet publish client-dotnet/Mensajeria.csproj -c Release -r win-x64 --self-contained true -o client-dotnet/bin/publish-win-x64
```

Arma estas 4 carpetas en el Escritorio (origen → destino):

| # | Carpeta a llevar | Contenido exacto |
|---|---|---|
| PC1 | `MensajeriaServidor/` | `servidor.jar` (renombrado desde `server/servidor-app/target/servidor-app-1.0.0-SNAPSHOT.jar`), `config/` (vacía + tu `usuarios.csv` si personalizas), `uploads/`, `logs/` (vacías) |
| PC2 | `MensajeriaClienteJava/` | `client-app.jar` + `lib/` + `javafx/` (jars `javafx-*-win` desde `lib/`) + `client.properties` + `launch-client.cmd` + README |
| PC3 | `MensajeriaPython/` | Contenido de `client-python/` sin `.venv`, `__pycache__`, `tests`, `*.db*` (ver `robocopy` en su README) |
| PC4 | `MensajeriaWPF/` | Contenido completo de `client-dotnet/bin/publish-win-x64` |

## Paso 1 — PC1 Servidor (`IP_SERVIDOR`, ej. `192.168.1.20`)

1. Instala Java 21 (JRE basta) + Docker Desktop.
2. Copia `MensajeriaServidor/` (p. ej. `C:\Mensajeria\`). Trae también `docker-compose.yml` solo para levantar MySQL, o instala MySQL 8.4 directo.
3. `docker compose up -d mysql` → verifica `docker ps` (puerto `3307`, base `mensajeria_db`, `root/12345` — desarrollo; en serio crea usuario propio).
4. Arranca con la IP y semilla explícitas (cuentas demo: `A001234567`/`B002345678`/`C003456789`/`D004567890`, todas con `Secreta123` según `usuarios_iniciales.csv`):

```powershell
cd C:\Mensajeria
java -jar .\servidor.jar --db.mysql.url="jdbc:mysql://127.0.0.1:3307/mensajeria_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Bogota" --db.mysql.user=root --db.mysql.pass=12345 --red.puerto=5000 --app.consola=true --app.escritorio=false
```

Flyway migra solo; comprueba `Login OK` al probar después.

5. Firewall (elevada, solo LAN privada): regla entrada `TCP 5000`, `RemoteAddress LocalSubnet`. **No abras MySQL (`3307`/`3306`) a la LAN.**

## Paso 2 — PC2 Java / PC3 Python / PC4 .NET (uno por máquina)

Común primero: `Test-NetConnection IP_SERVIDOR -Port 5000` → `TcpTestSucceeded: True`. Si falla: IP, firewall de PC1, servidor arriba.

- **PC2**: instala JRE 21 → copia `MensajeriaClienteJava/` → edita `client.properties`: `host=IP_SERVIDOR`, `puerto=5000` (H2 queda local `~/mensajeria_cliente`) → `launch-client.cmd` → login `A001234567`/`Secreta123`.
- **PC3**: instala Python 3.13 → copia `MensajeriaPython/` → `py -3.13 -m venv .venv` → `.\.venv\Scripts\python.exe -m pip install -r requirements.txt` → edita `client.properties` igual → `.\.venv\Scripts\python.exe main.py` → login `B002345678`.
- **PC4**: sin runtime (self-contained) → copia `MensajeriaWPF/` → edita `client.properties` junto al exe → `Mensajeria.exe` → login `C003456789`. (Reserva `D004567890` para la prueba de 2 sesiones.)

## Paso 3 — Smoke por máquina y matriz 6 direcciones

1. Cada cliente ve a los otros en línea (presencia push).
2. Por dirección (ver tabla en `GUIA_ACEPTACION.md`): texto, solo-imagen, texto+imagen, respuesta a texto, respuesta a imagen; verifica burbuja propia→derecha, ✓/✓✓/azul, sin duplicados ni contra-acuses.
3. Sesiones: `D004567890` en PC2 + otra máquina → `LOGOUT` no tumba la otra; «Salir en todos» sí. Cierra/reabre cada cliente → historial local intacto.
4. Imágenes: en PC1 comprueba `uploads/Nombre Apellido [CODIGO]/<hash>/` con original + 5 derivados; descarga en cliente tras reinicio.
5. Admin: en consola PC1 `informe conexiones` (sube solo por login), `cerrar <codigo>`; `informe mensajes/auditoria`.

## Paso 4 — Mantenimiento

- Respaldar MySQL + `uploads/` juntos (rutas en BD son relativas). Actualizar = reemplazar binarios, conservar `client.properties`, H2/SQLite e imágenes. Nunca copiar bases locales entre PCs.
