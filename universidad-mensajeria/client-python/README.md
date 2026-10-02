# Despliegue del cliente Python

Cliente de escritorio PySide6/QFluentWidgets. Esta carpeta contiene la aplicación Python y su
copia de `store/schema-local.sql`; no necesita el monorepo ni acceso a la base MySQL del servidor.

## Requisitos e instalación

Windows 10/11 de 64 bits y Python 3.13 recomendado (es el runtime de validación). Las versiones
de PySide6, QFluentWidgets y Pillow están fijadas en `requirements.txt`.

Desde la carpeta `client-python`:

```powershell
py -3.13 -m venv .venv
.\.venv\Scripts\python.exe -m pip install --upgrade pip
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe main.py
```

Inicia siempre desde la carpeta de la aplicación: `client.properties` se busca relativo al
directorio de trabajo. Instala la carpeta bajo un directorio donde el usuario tenga permisos de
escritura; las imágenes recibidas se almacenan en la subcarpeta `imagenes`.

## Preparar y copiar la carpeta

Para pasar solo Python a otra PC, copia el contenido de `client-python`, excluyendo el entorno
virtual local, las cachés y las pruebas. No copies `.venv`: contiene rutas y binarios ligados al
equipo donde se creó.

```powershell
$dist = "$env:USERPROFILE\Desktop\MensajeriaPython"
robocopy . $dist /E /XD .venv __pycache__ tests /XF *.db *.db-shm *.db-wal
```

`robocopy` puede devolver códigos `1` a `7` cuando la copia terminó correctamente. En el destino,
repite los pasos de instalación del entorno virtual y ejecuta `main.py`.

## Configuración

Edita `client.properties` en la carpeta del cliente:

```properties
host=192.168.1.20
puerto=5000
db.url=sqlite:///~/mensajeria_cliente.db
db.user=
db.pass=
idioma=es
```

Reemplaza `192.168.1.20` por la IP LAN o el nombre DNS del servidor. `localhost` solo sirve cuando
Python y el servidor están en el mismo computador. `puerto` debe coincidir con `red.puerto` del
servidor. El cliente necesita salida TCP a ese puerto; no requiere puertos entrantes ni acceso a
MySQL.

SQLite vive en el perfil del usuario y se deriva una ruta por código de cuenta. El primer acceso
migra de manera aditiva el archivo legado compartido si existe. No borres ese archivo antes de
iniciar por primera vez. `store/schema-local.sql` está incluido para que la migración y el primer
arranque también funcionen cuando solo se distribuye esta carpeta.

## Funcionalidad

- Acceso con código y contraseña (sin registro); la ventana muestra servidor
  y puerto con opción de recordarlos.
- El directorio trae todos los usuarios del servidor como
  `Nombre Apellido [CÓDIGO]`, con filtro Todos/Conectados, buscador y conteo
  de no leídos; la sesión indica el nombre propio. El historial remoto se
  fusiona sin duplicar burbujas ya recibidas en vivo.
- Enviar admite texto, imagen o archivo genérico hasta 50 MB por el
  protocolo fragmentado (`ARCHIVO_INICIO/PARTE/FIN`); el `FIN` recibe `ACK`
  del servidor. Los archivos recibidos se descargan bajo demanda.
- La difusión del servidor aparece como mensaje de `SERVIDOR`.

## Uso y solución de problemas

- El registro por red está eliminado; solicita al administrador código y contraseña.
- Si no conecta, prueba `Test-NetConnection IP_SERVIDOR -Port 5000` y revisa firewall/IP/puerto.
- Si la UI dice que envió pero no aparece el mensaje, conserva los logs/estado local y verifica el
  acuse y la conexión; no edites SQLite para “forzar” un visto.
- Conserva juntos `main.py`, paquetes (`ui`, `fachada`, `componentes`, `store`, `transversal`),
  `requirements.txt`, `client.properties` y `store/schema-local.sql`.
- Para actualizar, reemplaza los archivos de aplicación pero conserva la base SQLite del usuario.

## Pruebas de desarrollo

Desde esta carpeta y con las dependencias instaladas:

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests
```
