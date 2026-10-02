# Proyecto fuente y pruebas

Esta carpeta es el monorepo Maven usado para compilar y probar los componentes Java. No se copia
completa a los computadores finales: construye la salida del servidor o del cliente Java y entrega
cada distribución por separado.

## Comandos de desarrollo

```powershell
# MySQL de desarrollo
docker compose up -d mysql

# Servidor y cliente JavaFX, incluidos sus módulos Maven hermanos
mvn -pl server/servidor-app,client/client-app -am test

# Ejecutar servidor local
mvn -pl server/servidor-app -am package -DskipTests
java -jar server/servidor-app/target/servidor-app-1.0.0-SNAPSHOT.jar

# Ejecutar cliente JavaFX local
mvn -pl client/client-app -am install -DskipTests
mvn -pl client/client-app javafx:run
```

El servidor usa MySQL local de Docker en `localhost:3307`; el test E2E de informes requiere que
este servicio esté iniciado.

## Guías de componente

- Servidor: [server/servidor-app/README.md](server/servidor-app/README.md)
- JavaFX: [client/README.md](client/README.md)
- Python: [client-python/README.md](client-python/README.md)
- .NET/WPF: [client-dotnet/README.md](client-dotnet/README.md)
- Pruebas integradas: [GUIA_ACEPTACION.md](../GUIA_ACEPTACION.md)
- Topología e instalación separada: [DESPLIEGUE.md](../DESPLIEGUE.md)

Consulta también `common-protocol/PROTOCOLO.md` y `common-protocol/HISTORIAL_LOCAL.md` antes de
cambiar el contrato de red o la persistencia local común.
