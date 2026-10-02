# Servidor de mensajería (Fase 11.4): construcción multi-etapa.
# El JAR ya incluye la app; solo necesita Java 21, MySQL y discos persistentes.
# Uso: docker compose build servidor && docker compose up -d servidor

# ---- Etapa 1: compilación (Maven + JDK 21) ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY common-protocol ./common-protocol
COPY server ./server
COPY client ./client
RUN mvn -pl server/servidor-app -am package -DskipTests -q -Dmaven.test.skip=true

# ---- Etapa 2: ejecución (solo JRE 21) ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/server/servidor-app/target/servidor-app-*.jar ./servidor.jar
RUN mkdir -p /app/uploads /app/logs /app/config
VOLUME ["/app/uploads", "/app/logs", "/app/config"]
EXPOSE 5000
ENTRYPOINT ["java", "-jar", "/app/servidor.jar", \
  "--app.consola=false", \
  "--app.escritorio=false", \
  "--red.upload-dir=/app/uploads", \
  "--logging.file.name=/app/logs/server.log"]
