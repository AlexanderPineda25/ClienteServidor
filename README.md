# Mensajería académica

Aplicación de escritorio cliente/servidor sobre TCP y JSON. El servidor Java procesa mensajes y
almacena datos en MySQL; los clientes JavaFX, PySide6 y WPF mantienen historial/cache local H2 o
SQLite. Los clientes no se conectan a la base del servidor.

## Llevar a otro computador

Lee primero [DESPLIEGUE.md](DESPLIEGUE.md). Cada proceso tiene su propia guía dentro de su carpeta:

| Componente | Guía |
|---|---|
| Servidor Java + MySQL | [server/servidor-app/README.md](universidad-mensajeria/server/servidor-app/README.md) |
| Cliente JavaFX | [client/README.md](universidad-mensajeria/client/README.md) |
| Cliente Python | [client-python/README.md](universidad-mensajeria/client-python/README.md) |
| Cliente .NET/WPF | [client-dotnet/README.md](universidad-mensajeria/client-dotnet/README.md) |

En la red, los clientes conectan a `IP_SERVIDOR:5000/TCP`. Solo el servidor necesita alcanzar
MySQL. `localhost` no sirve como dirección de servidor en una aplicación cliente ubicada en otro
PC.

## Desarrollo y validación

El directorio `universidad-mensajeria/` es el monorepo de construcción, no la carpeta que se instala
en cada equipo. Requiere JDK 21, Maven 3.9+, Docker Compose para MySQL de prueba, Python según
`client-python/requirements.txt` y .NET SDK para WPF.

```powershell
cd universidad-mensajeria
docker compose up -d mysql
mvn -pl server/servidor-app,client/client-app -am test
```

La [guía de aceptación](GUIA_ACEPTACION.md) contiene las pruebas Python/.NET y la matriz manual de
las seis direcciones.

## Referencias

- [REQ.md](REQ.md): brief original del proyecto.
- [Requerimientos](REQUERIMIENTOS.md): contrato funcional.
- [PLAN.md](PLAN.md): arquitectura y decisiones de diseño.
- [ESTADO_ROADMAP.md](ESTADO_ROADMAP.md): estado y trabajo futuro.
- [PROTOCOLO.md](universidad-mensajeria/common-protocol/PROTOCOLO.md): framing y mensajes TCP/JSON.
- [HISTORIAL_LOCAL.md](universidad-mensajeria/common-protocol/HISTORIAL_LOCAL.md): esquema y
  comportamiento común de persistencia local.
