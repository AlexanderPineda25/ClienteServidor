# Requerimientos

Se requiere una aplicación para enviar mensajes a la comunidad académica de la Universidad, los mensajes pueden contener: texto o imágenes. La aplicación de software debe implementar las siguientes funcionalidades:

## Servidor

- Cada usuario tiene registrado su código, nombres, apellidos, contraseña y su programa académico. Los usuarios serán validados sobre su: código y contraseña. Inicialmente los usuarios estarán registrados en un archivo plano o una base de datos externa.
- Se debe restringir el número de usuarios conectados a la red, utilizando archivos de configuración.
- Se debe registrar los logs de los mensajes enviados entre los usuarios, los logs se almacenarán en una base de datos relacional. Adicionalmente, se debe guardar en un directorio toda la información enviada por los usuarios.
- La información se almacenará en una base de datos relacional (Mysql), deben utilizar archivos de configuración para configurar la conexión.
- Todos los mensajes deben almacenarse en una o más estructura de datos, y desde allí se deben consultar la información de los mismos.
- Los mensajes que se reciban deben procesar así:
  - **Texto:**
    - Obtener hash con sha256.
    - Contar los caracteres.
    - Contar las palabras.
  - **Imágenes:**
    - Obtener hash con sha256.
    - Aplicar filtros: escala de grises, sepia, gritar 180 grados, brillo y reducción de tamaño; donde, cada filtro genera una nueva imagen que se debe almacenar. Los filtros se deben ejecutar secuencialmente.
- El servidor debe seguir la arquitectura indicada en el diagrama.
- Enviar mensajes a todos los usuarios.
- Un usuario se puede conectar más de una vez.
- Cerrar conexiones de los usuarios.
- Se debe mostrar la información de cada mensaje que llega por la consola.
- Informes de usuarios:
  - Usuarios registrados.
  - Usuarios conectados, indicar la cantidad de veces.
  - Listado de mensajes enviados. Detalle de cada mensaje.
  - Logs generados.

## Cliente

- Puede ver que usuarios estarán conectados. Todos los usuarios son visibles a la comunidad.
- Se podrá intercambiar mensajes (texto o archivos) con un usuario específico.
- La información se almacenará en una base de datos relacional local (H2Database), deben utilizar archivos de configuración para configurar conexión.

## Requisitos generales

- La aplicación cliente y servidor pueden ser aplicaciones de escritorio. Se requiere crear aplicaciones con capas y con componentes.
- Los clientes pueden ser programados en lenguajes de programación diferentes a Java.
- El protocolo de comunicación entre el cliente/servidor será TCP-IP.
- Se deben aplicar los principios de diseño: Inversión de dependencias, inyección de dependencias, fábricas de objetos entre otros.
