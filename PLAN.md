# PLAN DE ARQUITECTURA — Sistema de Mensajería Académica (Cliente/Servidor TCP)

> Fuente funcional: `REQ.md` (brief original) y `REQUERIMIENTOS.md` (especificación vigente).
> Este documento conserva arquitectura, componentes, decisiones de diseño y criterios de fases;
> no es la guía de instalación. Topología, puertos y empaquetado: `DESPLIEGUE.md`.
> Principios: SOLID, Patrones de Diseño, Clean Code e **Inversión de Dependencias**; el servidor
> procesa mediante **Pipes & Filters**. El estado de implementación y el backlog están resumidos en
> `ESTADO_ROADMAP.md`; las pruebas integradas, en `GUIA_ACEPTACION.md`.

---

## 1. Objetivo y alcance

Aplicación de escritorio **cliente-servidor** para enviar mensajes a la comunidad
académica (texto, imágenes o archivos), sobre **TCP-IP**, con capas y componentes.

| Incluido (según `REQ.md` raíz) | Excluido de este plan |
|---|---|
| Servidor escritorio/consola (Java) | Aplicación web MVC (Thymeleaf) |
| Cliente escritorio **Java (principal, JavaFX)** | API REST (`ApiUsuarioController`) |
| Cliente escritorio **Python (espejo, PySide6 + QFluentWidgets)** | WebSocket |
| Cliente escritorio **C#/.NET (espejo, WPF)** | Librerías UI pesadas de MENSAJERIA (TilesFX, FXGL, ControlsFX, etc.) |
| MySQL (servidor) + historial local **multilenguaje** | |
| Protocolo TCP-IP común JSON (**clientes multilenguaje**) | |

> NOTA 1: MENSAJERIA trae además web MVC + REST. Quedan **fuera de alcance** por
> decisión del usuario, pero la arquitectura por capas deja la puerta abierta para
> añadirlos sin tocar el núcleo (principio OCP).
>
> NOTA 2: los clientes **Python** y **C#** existen para **demostrar el requisito C5**
> ("clientes en lenguajes diferentes a Java"): son implementaciones espejo de la vista
> cliente contra el mismo `PROTOCOLO.md`. **La vista Java es la principal** con el
> alcance completo; las otras dos son espejos funcionales que prueban el protocolo.

### Trazabilidad `REQUERIMIENTOS.md` → este plan (estado 2026-10-01, verificado en código)

| Requisito | Sección del plan | Estado |
|---|---|---|
| Autenticación y usuarios (RF-S01–S04, S27, RF-C01, C06) | §5.1, §6.1, Fase 10.2 | ✅ (RF-S28 hash-en-CSV: Extra ⏳) |
| Conexiones e inactividad (RF-S05–S06, S29–S32) | §4.3, §5.1, Fase 10.1 | ✅ |
| + retorno a login tras TIMEOUT (RF-C08) | Fase 12.4 | ✅ Java, Python y C# |
| Mensajería (RF-S07–S10, RF-C02–C03, C09–C14) | §3.2/§3.3, §7.1, Fases 5–6/10.3 | ✅ |
| Procesamiento texto/imagen (RF-S11–S17, S33) | §3 | ✅ |
| Persistencia y logs (RF-S18–S22, RF-C04–C05, C15) | §5 | ✅ (+ RF-C16 flush: Extra ⏳ 11.1) |
| Informes (RF-S23–S26) | §8, Fase 9 | ✅ |
| Consola y refresco (RF-S34–S42, RNF-11–12) | §6.12, Fases 10.4 y 12.2 | ✅ |
| Pool y concurrencia (RF-S43–S49, RNF-09, PD-05) | §9, Fase 12.1 | ✅ Pool real, saturación y estado (P1) |
| Arquitectura y diseño (RNF-01–02, RNF-10, PD-01–04, PD-06–08) | §2, §11, Fases 12.5–12.7 | ✅ |
| Comunicación/config/interop (RNF-03–08, RNF-13–15) | §4.0, §5, §7 | ✅ |
| Rendimiento cliente + cierre X (hallazgo, P0) | Fase 13 | ✅ |
| Stacks UI: AtlantaFX / PySide6+QFluent / WPF UI (P2) | Fase 14 | ✅ |
| UX extendida del cliente (RF-C17–C22) | Fases 15–17 | ⚠️ defectos manuales abiertos; reparación y smoke pendientes |
| Multiinstancia Java y sesiones aisladas | Fases 16–17 | ⏳ H2 por cuenta + migración; pruebas y dos ventanas |

### Pruebas de aceptación recopiladas (fuente: revisión en vivo 2026-10-01)

| # | Punto de prueba | Cubierto en |
|---|---|---|
| P1 | Consola/escritorio del servidor con **auto-refresco de vistas** (no solo botón «Refrescar») | Fase 10.4, criterio 17 |
| P2 | Los usuarios **no se registran desde el cliente**; solo autentican. El registro se hace en el archivo plano (CSV de siembra) | Fase 10.2, criterio 18 |
| P3 | Cierre de sesión por inactividad a **10 minutos** | Fase 10.1, criterio 19 |
| P4 | **Object Pool** para los múltiples hilos (red, filtros, pool de conexiones DB) | Fase 10.6 + §9, criterio 21 |
| P5 | `RESUMEN.md` + `ROADMAP.md` unificados en un solo `.md` | `ESTADO_ROADMAP.md` |
| P6 | Un **único botón «Enviar»** que envía texto y/o imagen (una sola imagen sin texto también válida) | Fase 10.3, criterio 20 |
| P7 | Fases 9–10 explícitas en este plan (informes + endurecimiento) | §12 |

> NOTA 3 (P2, cerrada en Fase 18): la vía `REGISTRO` por red está **eliminada**
> (`RegistroCommand`/`RegistrarCaso`/flag suprimidos); un `REGISTRO` entrante responde
> `ERROR "tipo no soportado: REGISTRO"`. La única forma de crear usuarios es
> `usuarios_iniciales.csv` (§5.1). Los tipos `REGISTRO/REGISTRO_RESPUESTA` se conservan
> en el catálogo solo como reserva histórica y las UIs nunca los emiten.

### Tecnologías

- **Servidor**: Java 21, JavaFX 21 (VistaEscritorio) + consola CLI. Multi-módulo Maven (§2.4). Spring Boot 3.5.7 (solo `data-jpa`, `validation`, `lombok`) **confinado a `servidor-app`** para la persistencia; los componentes son Java puro y la inyección de dependencias es manual con `Fabrica`.
- **Cliente Java (principal)**: Java 21, JavaFX 21, **H2** local.
- **Cliente Python (espejo)**: Python 3.13, **PySide6 + QFluentWidgets** para UI, **Pillow** para preview/decodificación de imágenes, `sqlite3` (stdlib). Dependencias fijadas en `client-python/requirements.txt`. Sin frameworks web.
- **Cliente C# (espejo)**: .NET 8, **WPF (XAML)** para UI, **Microsoft.Data.Sqlite 8.0.10 (fijado)** local, `System.Text.Json`. La suite de tests apunta a `net10.0` (requiere SDK 10); la app sigue en `net8.0-windows`.
- **MySQL 8** principal (servidor, vía Flyway); **PostgreSQL 16 y Oracle 23** opcionales
  conmutables por Adapter §5.4 (los 3 contenedores ya en `docker-compose.yml`).
- **Historial local multilenguaje**: esquema único **`common-protocol/schema-local.sql`**
  + queries canónicas **`common-protocol/HISTORIAL_LOCAL.md`**; motor nativo por
  cliente (H2 → Java, SQLite → Python/C#). Ver §5.2.
- **TCP-IP**: tramas `4 bytes longitud big-endian + payload JSON UTF-8` (**NO** serialización Java; ver §4.0). Contrato en `common-protocol/PROTOCOLO.md`.
- Módulos: `common-protocol`, `server` (proyecto padre multi-módulo, ver §2.4 y §11), `client` (Java), `client-python`, `client-dotnet`.

---

## 2. Arquitectura objetivo (100 % fiel al diagrama)

> Fuente de verdad: `ClienteServidor-P2P-Otros-ComponentesServidor.png`.
> **Regla de lectura:** una flecha del diagrama significa «A usa a B en tiempo de ejecución».
> En el código, cada uso se compila contra una **abstracción** (DIP, §2.5). En el plan no existe
> ninguna relación de uso que el diagrama no dibuje, y no falta ninguna de las que dibuja.

### 2.0 Inventario del diagrama (23 nodos · 16 flechas · 5 lollipops)

| Grupo del diagrama | Nodos |
|---|---|
| Arranque y vistas (paquetes) | `InicioServidor`, `Vista Consola`, `Vista Escritorio` |
| Servicios: paquetes | `FachadaDeServicios`, `Mensajes`, `LogDeEventos`, `ConexionesClientes`, `AlmacenarInformación` |
| Servicios: **componentes** (10) | 5 blancos «Interfaz…» + 5 amarillos de implementación: `ProtocoloComunicacion`, `ProtocoloClienteServidor`, `Servicios`, `Usuarios`, `GestorColasDeMensajes` |
| Persistencia | paquete `Persistencia` → nodo externo `BASE DE DATOS` |
| Transversal (paquetes, sin flechas) | `DTO-Records`, `UtileriasVarias`, `Fachada` |

| Id | Flecha del diagrama |
|---|---|
| E1, E2 | `InicioServidor` → `VistaConsola` · `InicioServidor` → `VistaEscritorio` |
| E3, E4 | `VistaConsola` → `FachadaDeServicios` · `VistaEscritorio` → `FachadaDeServicios` |
| E5–E9 | `FachadaDeServicios` → cada una de las 5 «Interfaz…» |
| E10–E12 | `FachadaDeServicios` → `Mensajes` · `LogDeEventos` · `ConexionesClientes` |
| E13, E14 | `Mensajes` → `AlmacenarInformacion` · `LogDeEventos` → `AlmacenarInformacion` |
| E15, E16 | `AlmacenarInformacion` → `Persistencia` · `Persistencia` → `BASE DE DATOS` |
| L1–L5 | lollipop «InterfazX» ⟷ «X» (la implementación realiza la interfaz) |

**Las ausencias también son parte del diagrama y el plan las respeta:** las 5 implementaciones no tienen
más conexión que su lollipop; `ConexionesClientes` no tiene flechas salientes; solo `Mensajes` y
`LogDeEventos` llegan a `AlmacenarInformacion`; las vistas solo llegan a la fachada; el grupo transversal no
dibuja flechas (es de uso libre, dentro de los límites de §2.4).

### 2.1 Regla de flujo (obligatoria)

```
InicioServidor  (incluye `Fabrica`: raíz de composición)
   ├─→ VistaConsola ──────────────┐
   └─→ VistaEscritorio ───────────┤  (solo vista; sin lógica de negocio)
                                  ▼
                        FachadaDeServicios  (FACADE + MEDIADOR: único nodo que conecta a los demás)
        ┌────────────────────────────────────────────────────────────────┐
        │ 5 flechas a componentes; cada «Interfaz…» es UN módulo y su    │
        │ implementación es OTRO módulo, unidos por el lollipop:         │
        │ InterfazProtocoloComunicacion ⟷ ProtocoloComunicacion          │
        │ InterfazClienteServidor       ⟷ ProtocoloClienteServidor       │
        │ InterfazServiciosDisponibles  ⟷ Servicios                      │
        │ InterfazUsuariosDisponibles   ⟷ Usuarios                       │
        │ InterfazGestorMensajes        ⟷ GestorColasDeMensajes          │
        └────────────────────────────────────────────────────────────────┘
        3 flechas a paquetes:  Mensajes · LogDeEventos · ConexionesClientes
                                  │            │         (solo memoria;
                                  └─────┬──────┘          SIN flechas salientes)
                                        ▼
                              AlmacenarInformacion   ← ÚNICO punto que toca Persistencia
                                        ▼
                                   Persistencia ──→ BASE DE DATOS (MySQL · Postgres/Oracle opcionales §5.4)

Tuberías (Pipes & Filters) invocadas POR Mensajes:
  Texto : SHA256 → ContarCaracteres → ContarPalabras
  Imagen: SHA256 → Grises → Sepia → Giro180 → Brillo → Reducción  (SECUENCIAL, §3.3)
  Archivo: SHA256                                                  (MENSAJE_ARCHIVO genérico, §3.2)
  Paralelismo: solo entre mensajes distintos (pool `filtros.hilos`, una cadena secuencial por mensaje, §3.4)
  Filtros y fábrica de tuberías viven en UtileriasVarias (módulo `utilerias`).

Transversal (sin flechas): DTO-Records · UtileriasVarias · Fachada
```

### 2.2 Elementos y su dueño exacto

| Elemento del diagrama | Unidad en el plan | Nota |
|---|---|---|
| `InicioServidor` | clase `InicioServidor` (paquete `inicio`) | Construye el grafo con `Fabrica` (mismo paquete) y arranca las dos vistas (E1, E2) |
| `Vista Consola` | `VistaConsola` | Habla SOLO con el contrato `Fachada` (E3) |
| `Vista Escritorio` | `VistaEscritorio` (JavaFX/FXML) | Habla SOLO con el contrato `Fachada` (E4) |
| `FachadaDeServicios` | `FachadaDeServicios` implements `Fachada` | Inyecta por constructor SOLO abstracciones: las 5 «Interfaz…» (E5–E9) + `InterfazMensajes`, `InterfazLogDeEventos`, `InterfazConexionesClientes` (E10–E12). Es el mediador (§2.5) |
| «InterfazProtocoloComunicacion» Component | **módulo** `int-protocolo-comunicacion` | `InterfazProtocoloComunicacion` + callback `ReceptorDeTramas` + `ProveedorProtocoloComunicacion` |
| «InterfazClienteServidor» Component | **módulo** `int-cliente-servidor` | `InterfazClienteServidor` + puertos de salida `SalidaClienteServidor` + proveedor |
| «InterfazServiciosDisponibles» Component | **módulo** `int-servicios-disponibles` | `InterfazServiciosDisponibles` + proveedor |
| «InterfazUsuariosDisponibles» Component | **módulo** `int-usuarios-disponibles` | `InterfazUsuariosDisponibles` + proveedor |
| «InterfazGestorMensajes» Component | **módulo** `int-gestor-mensajes` | `InterfazGestorMensajes` + callback `ConsumidorDeMensajes` + proveedor |
| «ProtocoloComunicacion» Component | **módulo** `comp-protocolo-comunicacion` | Transporte y tramas: `ServidorTCP`, `ClienteHandler`, `TramaCodec`; sockets internos |
| «ProtocoloClienteServidor» Component | **módulo** `comp-protocolo-clienteservidor` | Comandos: `CommandHandler` + `commands/`. No conoce la red ni a otros componentes |
| «Servicios» Component | **módulo** `comp-servicios` | Reglas: límites, validación de archivos, armado de informes. Lógica pura sobre DTOs |
| «Usuarios» Component | **módulo** `comp-usuarios` | Directorio en memoria: registro, validación código/contraseña, listado con estado |
| «GestorColasDeMensajes» Component | **módulo** `comp-gestor-colas` | `BlockingQueue` + índice de mensajes |
| `Mensajes` | paquete `nucleo.mensajes`: `InterfazMensajes` + `Mensajes` | Único que invoca las tuberías; persiste llamando a `AlmacenarInformacion` (E13) |
| `LogDeEventos` | paquete `nucleo.logdeeventos`: `InterfazLogDeEventos` + `LogDeEventos` | Observer; persiste eventos y auditoría vía `AlmacenarInformacion` (E14) |
| `ConexionesClientes` | paquete `nucleo.conexiones`: `InterfazConexionesClientes` + `ConexionesClientes` | Mapa en memoria de sesiones (`IdSesion`); **sin** flechas salientes |
| `AlmacenarInformación` | paquete `nucleo.almacenarinformacion`: `InterfazAlmacenarInformacion` + `AlmacenarInformacion` + `PersistenciaPort` | ÚNICO punto de acceso a `Persistencia` (E15) |
| `Persistencia` | paquete `persistencia` | Entidades JPA + repositorios + adaptadores del `PersistenciaPort` + directorio `uploads/` (E16 → BD) |
| `BASE DE DATOS` | MySQL 8 (Postgres/Oracle opcionales, §5.4) | Nodo externo |
| `DTO-Records` | módulo `dto` | Records del wire y Records internos, sin dependencias |
| `UtileriasVarias` | módulo `utilerias` | Framework `Filtro`/`Tuberia`/`TuberiaFactory` + filtros (§3); solo lo usa `Mensajes` |
| `Fachada` (transversal) | paquete `transversal.fachada` | Contrato `Fachada`: lo que las vistas ven de `FachadaDeServicios` |

### 2.3 Paquetes de `servidor-app`

| Paquete | Elementos del diagrama | Responsabilidad |
|---|---|---|
| `inicio` | `InicioServidor` | Arranque, `Fabrica` (raíz de composición), lectura de `server.properties`, `SemillaUsuarios` (CSV) |
| `vistaconsola` · `vistaescritorio` | `VistaConsola`, `VistaEscritorio` | Solo llaman al contrato `Fachada` |
| `fachadadeservicios` | `FachadaDeServicios` | Mediador; subpaquete interno `casosdeuso/` (una clase por operación) |
| `mensajes` · `logdeeventos` · `conexionesclientes` · `almacenarinformacion` | los 4 paquetes homónimos | Cada uno expone una interfaz y esconde su implementación |
| `persistencia` | `Persistencia` | Entidades JPA, repositorios, adaptadores; accesible SOLO a través de `PersistenciaPort` |
| `transversal.fachada` | `Fachada` | Contrato de la fachada |

> Los nombres de paquete espejan 1-a-1 los nodos del diagrama (`vistaconsola/`, `fachadadeservicios/`…);
> el `DiagramaFidelidadTest` (§13.16) falla si aparece un paquete extra como `presentacion/` o `nucleo/`.

No hay paquete `dominio` (no existe en el diagrama): las entidades JPA viven dentro de `persistencia.entidades`
y el resto del sistema habla en DTO-Records (módulo `dto`).

## 2.4 Componentes como módulos Maven independientes

> En el diagrama hay **paquetes** (carpeta) y **componentes** (ícono de componente). Los **10 componentes**
> (5 «Interfaz…» + 5 implementaciones) son módulos Maven independientes, un `.jar` cada uno. Las
> «Interfaz…» son componentes de primera clase: **una por módulo**, no un módulo `interfaces` común.
> `DTO-Records` y `UtileriasVarias` también son módulos porque los comparten varios módulos.

| Unidad | Tipo | Módulo Maven | Depende de (compilación) |
|---|---|---|---|
| DTO-Records | transversal | `dto` | nada |
| UtileriasVarias | transversal | `utilerias` | `dto` |
| «InterfazProtocoloComunicacion» | componente | `int-protocolo-comunicacion` | `dto` |
| «InterfazClienteServidor» | componente | `int-cliente-servidor` | `dto` |
| «InterfazServiciosDisponibles» | componente | `int-servicios-disponibles` | `dto` |
| «InterfazUsuariosDisponibles» | componente | `int-usuarios-disponibles` | `dto` |
| «InterfazGestorMensajes» | componente | `int-gestor-mensajes` | `dto` |
| «ProtocoloComunicacion» | componente | `comp-protocolo-comunicacion` | `int-protocolo-comunicacion`, `dto`, `codec` (librería de tramas de `common-protocol`) |
| «ProtocoloClienteServidor» | componente | `comp-protocolo-clienteservidor` | `int-cliente-servidor`, `dto` |
| «Servicios» | componente | `comp-servicios` | `int-servicios-disponibles`, `dto` |
| «Usuarios» | componente | `comp-usuarios` | `int-usuarios-disponibles`, `dto` (+ BCrypt Java puro, sin Spring) |
| «GestorColasDeMensajes» | componente | `comp-gestor-colas` | `int-gestor-mensajes`, `dto` |
| Inicio, Vistas, Fachada, Mensajes, LogDeEventos, ConexionesClientes, AlmacenarInformacion, Persistencia, contrato Fachada | paquetes | `servidor-app` | los 5 `int-*`, `dto`, `utilerias`; los 5 `comp-*` **solo en scope `runtime`** |

**Reglas de independencia (verificables):**
- Un `comp-*` depende únicamente de **su** `int-*` y de `dto`. Nunca de otro `comp-*`, de otro `int-*`,
  de `utilerias` ni de `servidor-app`.
- Un `int-*` depende únicamente de `dto`.
- `servidor-app` compila **solo contra los `int-*`**; los `comp-*` se cargan en tiempo de ejecución.
- Los `comp-*` no llevan Spring ni anotaciones de framework. Spring Boot vive solo en `persistencia`.

**Ensamblaje (DI manual + `ServiceLoader`).** Cada `int-*` publica un SPI `Proveedor<Nombre>` y cada `comp-*`
lo registra en `META-INF/services`. `Fabrica` (paquete `inicio`, única clase autorizada a construir objetos) descubre
la implementación con `ServiceLoader`, la crea con la configuración y la inyecta por constructor. Cambiar de
implementación equivale a cambiar un `.jar`, sin tocar `servidor-app`. El empaquetado final usa
`maven-assembly-plugin` (`jar-with-dependencies`, `mainClass` = `InicioServidor`, con `metaInf-services` para
fusionar los registros).

**Beneficios verificables.** Cada módulo compila y se prueba solo, con dobles de sus callbacks; el árbol de
dependencias de `servidor-app` demuestra que no conoce ninguna implementación.

## 2.5 Inversión de dependencias en cada frontera

**Regla 1 — Flecha de uso ≠ dependencia de compilación.** Las flechas E1–E16 se ejecutan en runtime; en
compilación cada una apunta a una abstracción:

| Flecha | Cómo se compila |
|---|---|
| E1, E2 | `InicioServidor` instancia las vistas (raíz de composición) |
| E3, E4 | Vistas → contrato `Fachada` ← `FachadaDeServicios` |
| E5–E9 y L1–L5 | `FachadaDeServicios` → `int-*` ← `comp-*` (la fachada nunca ve un `comp-*`) |
| E10–E12 | `FachadaDeServicios` → interfaz del propio paquete (`InterfazMensajes`, `InterfazLogDeEventos`, `InterfazConexionesClientes`) ← clase |
| E13, E14 | `Mensajes` / `LogDeEventos` → `InterfazAlmacenarInformacion` ← `AlmacenarInformacion` |
| E15 | `AlmacenarInformacion` → `PersistenciaPort` (de su paquete) ← adaptadores de `persistencia` (**invertida en compilación**) |
| E16 | `persistencia` → BD (JPA/JDBC) |

Las interfaces de paquete (E10–E14) no son nodos nuevos del diagrama: son la API pública de cada paquete y
su clase queda oculta.

**Regla 2 — Cada `int-*` declara lo que ofrece y lo que necesita.** Lo que el componente necesita del mundo
exterior lo define él como callback en su propio módulo, y solo lo implementa la Fachada (que ya tiene la flecha
hacia ese `int-*`):

| Módulo `int-*` | Ofrece (lollipop) | Necesita (callback definido aquí, implementado por la Fachada) |
|---|---|---|
| `int-protocolo-comunicacion` | `iniciar(puerto)`, `detener()`, `enviar(IdSesion, Mensaje)`, `cerrarSesion(IdSesion, motivo)`, `registrarReceptor(...)` | `ReceptorDeTramas`: `alConectar(IdSesion, direccion)`, `alRecibir(IdSesion, Mensaje)`, `alDesconectar(IdSesion, motivo)` |
| `int-cliente-servidor` | `procesar(Mensaje, IdSesion)`, `registrarSalida(...)` | `SalidaClienteServidor`, dividida por rol (ISP): `SalidaAutenticacion` (autenticar, registrar, cerrar), `SalidaMensajeria` (recibirTexto/Imagen/Archivo, difundir, historial, descargar), `SalidaConsultas` (listarUsuarios), `SalidaRespuestas` (responder) |
| `int-servicios-disponibles` | `verificarLimiteConexiones`, `validarArchivo`, `armarInforme…` | nada (lógica pura sobre DTOs) |
| `int-usuarios-disponibles` | `validarCredenciales`, `registrar`, `listar`, `buscar`, `sembrar` | nada (directorio en memoria) |
| `int-gestor-mensajes` | `encolar`, `buscarPorId`, `conversacion(origen, destino, pagina)`, `registrarConsumidor(...)` | `ConsumidorDeMensajes`: `procesar(Mensaje)` |

**Regla 3 — Los componentes no se conocen entre sí; colaboran a través de la Fachada (mediador).** No existe
ninguna flecha entre `comp-*`, y la Fachada es el único nodo conectado a todos. Sus operaciones viven en
`fachada.casosdeuso` (una clase por operación) para no hacer una clase-dios. Ejemplo, un `MENSAJE_TEXTO`:

1. `ProtocoloComunicacion` recibe la trama y llama a `ReceptorDeTramas.alRecibir` (E5).
2. La Fachada llama a `InterfazClienteServidor.procesar` (E6); `TextoCommand` valida y pide
   `SalidaMensajeria.recibirTexto(...)`.
3. La Fachada consulta `InterfazServiciosDisponibles` (E7) y encola en `InterfazGestorMensajes` (E9); el
   consumidor llama a `Mensajes.procesarTexto` (E10), que ejecuta la tubería y persiste vía `AlmacenarInformacion` (E13 → E15 → E16).
4. Al completarse, la Fachada busca los destinos en `ConexionesClientes` (E12) y envía con
   `InterfazProtocoloComunicacion.enviar` (E5). El evento se registra en `LogDeEventos` (E11 → E14).

**Regla 4 — Solo `IdSesion` cruza fronteras.** Los sockets (`SesionCliente`, `ClienteHandler`) son internos de
`comp-protocolo-comunicacion`; el resto solo conoce el record `IdSesion` (módulo `dto`).

**Regla 5 — Asincronía y eventos sin flechas nuevas.**
- `Mensajes.procesarX(...)` devuelve `CompletableFuture<ResultadoMensajeDTO>`; la Fachada encadena el envío al terminar.
- Los eventos de etapa de las tuberías salen por un oyente (`Consumer<EventoEtapa>`) que registra la Fachada y reenvía a `LogDeEventos`; `Mensajes` no conoce a `LogDeEventos`.
- La consola (req. 12) se suscribe con `Fachada.suscribirEventos(...)`, que delega en `LogDeEventos`; `VistaConsola` no toca `LogDeEventos` (solo E3).

**Regla 6 — Datos de `Usuarios` y `Servicios` (el diagrama no les dibuja camino a la persistencia).**
- `Usuarios` guarda su directorio en memoria (§5.3). `Fabrica` lo siembra al arrancar (paso de construcción, no
  una flecha de uso) con `usuarios_iniciales.csv` y con los usuarios ya guardados en MySQL, leídos vía
  `AlmacenarInformacion`. Un `REGISTRO` en caliente se aplica en memoria y se persiste como evento
  `USUARIO_REGISTRADO` que la Fachada entrega a `LogDeEventos` (E11 → E14); `AlmacenarInformacion` lo escribe en `usuarios` y `registro_acciones`.
- `Servicios` es lógica pura: la Fachada le pasa los datos ya obtenidos de `Mensajes`, `LogDeEventos`,
  `ConexionesClientes` y `Usuarios`; sus límites estáticos (`max_conexiones`, tamaños) los recibe de `Fabrica` desde `server.properties`.
- Si se prefiere que `Usuarios` o `Servicios` lean y escriban directamente, primero hay que añadir esa flecha
  al **diagrama** (`→ AlmacenarInformacion`) y solo entonces al plan.

## 2.6 Correcciones respecto a la versión anterior del plan

| Antes | Ahora |
|---|---|
| Un módulo `interfaces` con todo | 5 módulos `int-*`, uno por cada «Interfaz…» del diagrama |
| Flechas extra: `Usuarios`/`Servicios` → `AlmacenarInformacion`; `ProtocoloClienteServidor` → `Mensajes`; `ProtocoloComunicacion` → `ProtocoloClienteServidor` | Eliminadas: colaboración por callbacks + Fachada (§2.5) |
| Contrato `SesionCliente` compartido | Record `IdSesion`; los sockets no salen de su componente |
| `InterfazMensajes`/`LogDeEventos`/`ConexionesClientes` en el módulo común | Dentro de su propio paquete (no son componentes en el diagrama) |
| Paquete `dominio`, paquete `fabrica`, `transversal.config` (no existen en el diagrama) | Eliminados: entidades en `persistencia`, `Fabrica` en `inicio`, excepciones en `dto` |
| `servidor-app` compilaba contra las implementaciones | Solo contra `int-*`; `comp-*` en `runtime` vía `ServiceLoader` |
| «El plan no se declara 100 % fiel» | Test de fidelidad automático (§13.16) |

---

## 3. Tuberías — Filtros y Tuberías (Pipes & Filters) (requisito "TENER EN CUENTA TUBERÍAS")

> **Requerimiento:** "Los filtros se deben ejecutar secuencialmente". Todas las tuberías son
> secuenciales; no existe modo paralelo dentro de una tubería.
>
> **Dueño:** las tuberías las invoca **`Mensajes`** (que, junto con `LogDeEventos`, tiene flecha a
> `AlmacenarInformacion` en el diagrama), nunca un servicio suelto. El framework y los filtros viven en
> el módulo `utilerias` (**`UtileriasVarias`** del diagrama); `Mensajes` solo los compone.
>
> **Base conceptual (material del curso):** cada tubería es unidireccional y punto a
> punto; cada filtro es autocontenido, independiente y sin estado; entre filtros viajan
> datos pequeños (rutas de archivo, no bytes); el paralelismo se da **entre mensajes
> distintos** (una cadena secuencial por mensaje, en hilos), nunca dentro de una cadena.

### 3.1 Contrato (en `utilerias`)

```java
public interface Filtro {                        // SIN estado: todo lo que produce sale en su resultado
    ResultadoFiltro ejecutar(List<File> entrada, ContextoTuberia ctx) throws FiltroException;
    String nombre();
}

public record ResultadoFiltro(
    List<File> archivos,               // salida = entrada del siguiente filtro
    Metadatos metadatos) {}            // datos calculados por ESTE filtro (hash, caracteres, palabras); inmutable

public record ContextoTuberia(         // inmutable, sin estado compartido
    Path directorioTrabajo,            // área temporal: los filtros escriben SOLO aquí
    Consumer<EventoEtapa> listener) {} // etapa, ms, hilo, ruta_salida → oyente que registra la Fachada y reenvía a LogDeEventos (§2.5)

public final class Tuberia {
    public Tuberia agregar(Filtro filtro);                        // Composite: filtros en orden
    public ResultadoTuberia ejecutar(List<File> entrada, ContextoTuberia ctx);
    // secuencial: la salida del filtro i es la entrada del filtro i+1; fusiona los Metadatos de cada resultado
}

public final class TuberiaFactory {
    public Tuberia texto();     // SHA256 → ContarCaracteres → ContarPalabras
    public Tuberia imagen();    // SHA256 → Grises → Sepia → Giro → Brillo → Reducción
    public Tuberia archivo();   // SHA256 (archivo genérico, sin transformación)
}
```

- Cada filtro es una **Strategy** independiente y sin estado (se prueba aislado; LSP entre filtros).
  Los metadatos no se mutan en un contexto compartido: cada filtro los devuelve en su
  `ResultadoFiltro` y `Tuberia` los fusiona en el `ResultadoTuberia`.
- Agregar o quitar una etapa no toca al resto (OCP).
- Los filtros **no** persisten: escriben solo en `directorioTrabajo` (área temporal). La
  promoción a `uploads/` y el registro en MySQL los hace `AlmacenarInformacion` (§3.5).

### 3.2 Tubería de TEXTO (secuencia exigida) y de ARCHIVO genérico

```
Mensajes.procesarTexto():
  archivo .txt original → [SHA256Filtro] → [ContarCaracteresFiltro] → [ContarPalabrasFiltro] → ResultadoTuberia

Mensajes.procesarArchivo():           (MENSAJE_ARCHIVO: PDF, documentos, etc.)
  archivo original → [SHA256Filtro] → ResultadoTuberia
```

El texto original se guarda primero como archivo en `uploads/` (requisito: "guardar en
un directorio toda la información enviada"). Resultado persistido: `hash_sha256`,
`num_caracteres`, `num_palabras`. Un archivo genérico se almacena en `uploads/`, se registra en
`archivos` con su `hash_sha256` y no recibe transformaciones.

### 3.3 Tubería de IMAGEN (secuencial; cada transformación genera una NUEVA imagen)

```
Mensajes.procesarImagen():
  original (png/jpeg) → [SHA256Filtro] → [GrisesFiltro] → [SepiaFiltro] → [GiroFiltro]
                      → [BrilloFiltro] → [ReduccionFiltro]
```

Cada transformación es su propio `Filtro` (`BufferedImage` + `ImageIO`, sin dependencias
extra) y recibe como entrada la salida del filtro anterior: **escala de grises, sepia, giro**
(ángulo por `filtros.giro.grados`, por defecto 180), **brillo** (`filtros.brillo.factor`) y
**reducción de tamaño** (`filtros.reduccion.factor`).

- El resultado son **5 imágenes derivadas + la original**, con nombre `01_grises`,
  `02_sepia`, `03_giro180`, `04_brillo`, `05_reduccion` (con su extensión). El orden lo da la
  posición en la cadena; cada derivada parte de la anterior y por tanto acumula los efectos previos.
- Existe un único modo de ejecución (secuencial); no hay configuración `filtros.modo`.
- Cada etapa emite un `EventoEtapa` (`etapa`, `ms`, `hilo`, `ruta_salida`) a su oyente; la Fachada lo reenvía a `LogDeEventos` (`Mensajes` no conoce a `LogDeEventos`: el diagrama no dibuja esa flecha)
  (satisface "mostrar la información de cada mensaje por consola" y "detalle de cada mensaje").

### 3.4 Concurrencia

- El paralelismo ocurre **entre mensajes distintos**: `Mensajes` lanza una tarea por
  mensaje/imagen (si llegan varios archivos, una tarea por archivo) en el pool `filtros.hilos`
  (`server.properties`), separado del pool de red de §4.3. Dentro de cada tarea la cadena de
  filtros es estrictamente secuencial.
- Un fallo en un filtro **detiene la cadena de esa imagen**: `ResultadoTuberia` indica la etapa
  fallida, no se promueve ninguna derivada a `uploads/` (el original ya guardado se conserva),
  `work/` se limpia, se emite un evento de error (la Fachada lo registra en `LogDeEventos`) y el remitente recibe `ERROR`.
  No existe resultado parcial ni reordenamiento de resultados.

### 3.5 Conexión con el resto del sistema

```java
// Mensajes.procesarTexto(...)   ← dueño de la tubería
File original = almacenar.guardarOriginal(contenido, remitente);        // uploads/
ResultadoTuberia r = tuberiaTexto.ejecutar(List.of(original), ctx);
almacenar.guardarMensajeTexto(r);              // hash + caracteres + palabras

// Mensajes.procesarImagen(...)
File original = almacenar.guardarOriginal(bytesImagen, remitente);
ResultadoTuberia r = tuberiaImagen.ejecutar(List.of(original), ctx);    // 5 derivadas en work/, en cadena
almacenar.guardarImagenProcesada(r);           // work/ → uploads/<hash>/01..05 + registro en MySQL

// Mensajes.procesarArchivo(...)
File original = almacenar.guardarOriginal(bytesArchivo, remitente);
ResultadoTuberia r = tuberiaArchivo.ejecutar(List.of(original), ctx);   // solo SHA-256
almacenar.guardarArchivo(r);                   // registro en `archivos`
```

### 3.6 Ejecución multiproceso (extra opcional)

Equivalente a `P1 | P2 | P3` de la pizarra del curso. El módulo `utilerias` incluye
`FiltroMain <filtro> [opciones]`: lee rutas por stdin (una por línea) y escribe las rutas
resultantes por stdout. Los procesos se enlazan con `ProcessBuilder.startPipeline`.
Sirve para demostrar el estilo de procesos independientes; el servidor usa el modo en
memoria.

---

## 4. Protocolo TCP (reutilizar MENSAJERIA + endurecer)

> **Sin capa `red/` paralela.** La red vive DENTRO de los componentes del diagrama:
> - `ProtocoloComunicacion` (implementa `InterfazProtocoloComunicacion`) → **transporte y
>   tramas** del servidor: dueño de `ServidorTCP`, `ClienteHandler` (sockets, internos) y el codec de
>   tramas (`TramaCodec`). Entrega cada trama decodificada al `ReceptorDeTramas` registrado (la
>   Fachada), identificando al cliente solo por `IdSesion`. No conoce a `ProtocoloClienteServidor`.
> - `ProtocoloClienteServidor` (implementa `InterfazClienteServidor`) → **comandos** del servidor:
>   dueño de los `CommandHandler`. Recibe `procesar(Mensaje, IdSesion)` y pide lo que necesita por los
>   puertos de salida (`SalidaClienteServidor`) que implementa la Fachada. No conoce a `Usuarios`,
>   `Mensajes` ni la red. El estado de las sesiones vive en `ConexionesClientes` (memoria).
> - `ConexionCliente` y `DetectorIP` son código del **cliente** (§7.1), no de ningún componente
>   del servidor: así el cliente no depende de módulos del servidor.

### 4.0 Wire protocol — lenguaje neutro (requisito C5: clientes ≠ Java)

> ❌ **Se elimina** la serialización Java nativa (`ObjectOutputStream`/`Shared/Mensaje`
> serializable): un cliente Python/C# no puede leerla.
> ✅ Se reemplaza por tramas neutras. Definidas en **`common-protocol/PROTOCOLO.md`**.

```
TRAMA = [4 bytes longitud big-endian][payload UTF-8 JSON]
payload = { "tipo": "<CATALOGO>", "id": <uuid>, "fechaHora": ISO-8601,
            "remitente": "...", "destinatario": "...", ...campos }
```

- **Catálogo de tipos** (Records en `common-protocol/dto/`, detalle completo en `PROTOCOLO.md`):
  - *Sesión*: `LOGIN`/`LOGIN_RESPUESTA`, `REGISTRO`/`REGISTRO_RESPUESTA` (⚠️ deshabilitado, Fase 10.2),
    `LOGOUT`, `KICK`, `CLOSE_NOTICE`, `SYNC_LOGIN`.
  - *Mensajería*: `MENSAJE_TEXTO`, `MENSAJE_IMAGEN`, `MENSAJE_ARCHIVO` (+`ACK`, `IMAGE_FILTERED_RESULT`),
    `BROADCAST`.
  - *Presencia y estado de entrega (push asíncrono, §2.5 regla 5)*: `PRESENCIA` (cliente↔servidor), 
    `MENSAJE_ENTREGADO` (servidor→remitente), `MENSAJE_LEIDO` (destinatario→servidor→remitente).
  - *Consultas*: `LISTAR_USUARIOS(_RESPUESTA)`, `LISTAR_CONECTADOS(_RESPUESTA)`, `HISTORIAL_REQ`/`HISTORIAL_PAGE`,
    `DESCARGAR_ARCHIVO(_RESPUESTA)`.
  - *Errores*: `ERROR`.
- **Estado de entrega** (`EstadoMensaje` en `dto`): `ENVIADO → ENTREGADO → LEIDO`; el servidor
  emite `MENSAJE_ENTREGADO` al entregar en destino, el cliente emite `MENSAJE_LEIDO` al abrir la
  conversación, y ambos flujos vuelven al remitente para pintar `✓ / ✓✓ / ✓✓ cyan` (§7.1).
- **Asincronía sin flechas nuevas (§2.5 regla 5)**: `PRESENCIA`, `MENSAJE_ENTREGADO` y `MENSAJE_LEIDO`
  viajan por el mismo transporte (`InterfazProtocoloComunicacion.enviar`) y se procesan como comandos
  más del `Map` de §4.1; el catálogo crece sin que aparezca ninguna flecha entre componentes.
- **Imágenes**: `contenido_imagen` via **Base64** dentro del JSON (o `DESCARGAR_ARCHIVO`
  bajo demanda); límite de tamaño por `server.properties` para no inflar tramas.
- **Archivos genéricos** (`MENSAJE_ARCHIVO`): `nombre`, `mime`, `tamano` y `contenido` (Base64 o
  descarga bajo demanda). Cubre "texto o archivos"; solo recibe hash SHA-256 (§3.2).
- **Usuarios visibles** (`LISTAR_USUARIOS`): devuelve TODOS los usuarios registrados con
  `estado` = `CONECTADO` | `DESCONECTADO`.
- **Codec**: servidor y cliente Java = `common-protocol/codec/` (Records + Gson);
  cliente Python = `struct` + `json` (stdlib). **El mismo contrato, 3 implementaciones.**
- `common-protocol/PROTOCOLO.md`: marcos, catálogo, ejemplos hex/JSON y errores.
- **Nota**: al cambiar la codificación, `serialVersionUID` deja de existir; los DTO
  pasan a ser **Records JSON** en `common-protocol/dto/`.

### 4.1 Refactor del `ClienteHandler` (god-class de 567 líneas → Command)

```
interface CommandHandler { void handle(Mensaje m, IdSesion s); }
Map<String, CommandHandler> commands;   // inyectado por Fabrica, vive en ProtocoloClienteServidor
// Entrada: ProtocoloComunicacion decodifica (TramaCodec) → Mensaje (Record JSON) y lo entrega al
//          ReceptorDeTramas (Fachada), que llama a InterfazClienteServidor.procesar(m, id)
// Salida : el handler NO usa la red ni otros componentes: pide lo necesario por SalidaClienteServidor
```

| Comando | Handler |
|---|---|
| `LOGIN`, `LOGOUT` | `LoginCommand` / `LogoutCommand` |
| `REGISTRO` | `RegistroCommand` → `RegistrarCaso` — **deshabilitado (Fase 10.2)**: con `registro.habilitado=false` responde `REGISTRO_RESPUESTA {exito=false}` «registro deshabilitado: contacte al administrador»; los usuarios solo se crean por `usuarios_iniciales.csv` (§5.1) |
| `MENSAJE_TEXTO` | `TextoCommand` → `SalidaMensajeria` (la Fachada delega en `Mensajes`, tubería texto) |
| `MENSAJE_IMAGEN` | `ImagenCommand` → `SalidaMensajeria` (la Fachada delega en `Mensajes`, tubería imagen) |
| `MENSAJE_ARCHIVO` | `ArchivoCommand` → `SalidaMensajeria` (la Fachada delega en `Mensajes`, tubería archivo) |
| `BROADCAST` | `BroadcastCommand` (a TODOS los usuarios) |
| `LISTAR_CONECTADOS` / `LISTAR_USUARIOS` | `ListarCommand` / `ListarUsuariosCommand` |
| `MENSAJE_LEIDO` | `LeidoCommand` (el lector avisa al autor: reenvía sin cola, marca `LEIDO` en origen) |
| `HISTORIAL_REQ` | `HistorialCommand` (paginado) |
| `DESCARGAR_ARCHIVO` | `DescargaCommand` |
| `CERRAR_CONEXION` / `KICK` | `CierreCommand` |

Solo `MENSAJE_LEIDO` es un comando entrante con handler; `MENSAJE_ENTREGADO` y `PRESENCIA` son
**push del servidor** (los construye `FachadaDeServicios`/`AutenticarCaso`/`CerrarCaso` y salen por
`InterfazProtocoloComunicacion.enviar`), no hay `EntregadoCommand` ni `PresenciaCommand`.

Nuevo mensaje **tipado** al catálogo: `BROADCAST`, `IMAGE_FILTERED_RESULT`
(devuelve las 5 rutas/filtros al remitente), `CLOSE_NOTICE` (aviso de cierre/kick),
y los push asíncronos `MENSAJE_ENTREGADO`, `MENSAJE_LEIDO`, `PRESENCIA` (§4.0).

### 4.2 Historial PERSISTENTE y PAGINADO (decisión acordada)

> ✅ **El chat SÍ persiste.** Lo único que se elimina es devolver imágenes completas
> (`byte[]`) en bloque al abrir la conversación.

- Cliente: `HISTORIAL_REQ {origen, destino, pagina, tamano}`.
- Servidor: `HISTORIAL_PAGE {mensajes SIN bytes, paginaActual, totalPaginas}` →
  consulta MySQL (`mensajes`) **y/o** cola en memoria `GestorColasDeMensajes`.
- Las imágenes se obtienen aparte con `DESCARGAR_ARCHIVO {id}` (bajo demanda) y,
  si existe en H2 local, se sirven sin red.
- Cliente persiste su copia local en **H2** (Java) o **SQLite** (Python)
  (`historial_local`) → historial offline.

Resultado: chat persiste en **MySQL (servidor) + H2/SQLite (clientes) + directorio `uploads/`**,
sin cargar `byte[]` completos en RAM ni bloquear el hilo TCP.

### 4.3 Concurrencia

- Reemplazar `Executors.newCachedThreadPool()` (ilimitado → riesgo de OOM) por
  un `ThreadPoolExecutor` acotado (Java puro, sin Spring) con `core/max` desde `server.properties`
  (**restricción de usuarios conectados por archivo de configuración**).
- Mapa de conexiones (en `ConexionesClientes`): `ConcurrentHashMap<codigoUsuario, Set<IdSesion>>`
  (no `static`; inyectado → testeable). Los sockets reales (`SesionCliente`) son internos de
  `comp-protocolo-comunicacion` y no cruzan su frontera.
- Escritura: canal por sesión con lock (`synchronized`) + `flush()`; el payload lo
  serializa `TramaCodec` (JSON), no `ObjectOutputStream`.
- Un usuario puede conectarse **varias veces** → `Set` de sesiones por código,
  broadcast envía a todas las sesiones de cada usuario.
- Las tuberías usan su propio pool (`filtros.hilos`, §3.4), separado del pool de red.
- **Límite de conexiones** (`max_conexiones`): cuenta **sesiones TCP activas**, no usuarios
  distintos; la 2.ª conexión de un mismo código consume cupo y, al alcanzar el límite, la
  siguiente se rechaza con `ERROR`.

---

## 5. Persistencia y configuración

> **Regla (corrección 6):** solo **`AlmacenarInformacion`** invoca `Persistencia`.
> Ningún componente ni servicio toca JPA/H2/disco directamente: pasan por ella.
> **Regla (corrección 3):** **`ConexionesClientes`** es SOLO memoria viva; no tiene
> flecha a `Persistencia` en el diagrama. El registro de sesiones/conexiones en MySQL
> lo dispara `LogDeEventos` (evento `CONECTADO/DESCONECTADO`), no `ConexionesClientes`.

### 5.1 Servidor — MySQL principal (Flyway, `ddl-auto=none`; multi-motor vía §5.4)

| Tabla | Contenido |
|---|---|
| `usuarios` | `codigo`, `nombres`, `apellidos`, `password_hash` (BCrypt), `programa_academico` |
| `conexiones` | ip, hora_conexion/desconexion, sesion_activa, veces_conectado |
| `mensajes` | remitente, destinatario, tipo, contenido, `hash_sha256`, `num_caracteres`, `num_palabras`, `etapas_filtrado` (JSON), fecha |
| `archivos` | nombre, tamano, mime, `ruta_directorio`, hash |
| `registro_acciones` | logs de todos los eventos (auditoría) |
| `configuracion_limites` | max_conexiones, max_tamano_archivo, puerto, uploadDir |

- **Seed de usuarios (única vía de registro — P2)**: archivo plano `usuarios_iniciales.csv` importado
  en arranque (paso de arranque de `Fabrica` vía `AlmacenarInformacion`, §2.5 regla 6) — cubre
  "inicialmente los usuarios estarán registrados en un archivo plano o base de datos externa".
  El alta en caliente por red (`REGISTRO`) queda **deshabilitada** (Fase 10.2): el cliente solo
  autentica (`LOGIN`).
- **Timeout de inactividad**: `red.sesion.timeout-segundos=600` (10 minutos, P3) en
  `server.properties`; `socket.setSoTimeout(...)` en `ManejadorConexion` cierra la sesión y
  `LogDeEventos` registra `TIMEOUT` + `CLOSE_NOTICE` al cliente (§6.11, `[RF-S08]`).
- Conexión por `application.properties` + `server.properties` externo
  (`SPRING_CONFIG_ADDITIONAL_LOCATION`).
- **Directorio de archivos**: `uploads/` (originales + 5 derivados por imagen).
- **Directorio de trabajo** `work/`: área temporal donde escriben los filtros (§3.1). No es persistencia: `AlmacenarInformacion` promueve los resultados a `uploads/` y lo limpia.
- **Logs**: por un lado `LogDeEventos` → tabla `registro_acciones` (DB relacional);
  por otro, Logback → `logs/server.log` (archivo plano). Ambos exigidos por la rama MENSAJERIA.

### 5.2 Cliente — historial local multilenguaje (contrato neutro)

> El historial local **debe funcionar en cualquier lenguaje**. Por eso el esquema y
> las queries viven en `common-protocol/` (contrato compartido), no en el código de
> un cliente concreto.

- **`common-protocol/schema-local.sql`** — esquema ÚNICO dialecto-neutro
  (solo `TEXT`, `INTEGER`, `REAL`, `BLOB`; sin secuencias propietarias ni tipos de
  motor): `historial_local`, `cache_usuarios`, `pendientes_envio`.
- **`common-protocol/HISTORIAL_LOCAL.md`** — queries canónicas UNA sola vez, con el
  SQL exacto que los 3 clientes ejecutan igual:
  - `upsertMensaje(...)` (recibir/enviar)
  - `paginaConversacion(origen, destino, offset, limit)` (historial paginado offline)
  - `marcarDescargado(idMensaje)`
  - `pendientesEnvio()` / `eliminarPendiente(id)`
  - `upsertUsuarioCache(...)`
- **Motor nativo por cliente** (mismo esquema, mismas queries, driver propio):

| Cliente | Motor | Config |
|---|---|---|
| Java (principal) | **H2** (JDBC) | Base legacy en `db.url`; nuevo archivo `mensajeria_cliente_<CODIGO>` con `AUTO_SERVER=TRUE` |
| Python (espejo) | **sqlite3** (stdlib) | `db.url=sqlite:///~/mensajeria_cliente.db` |
| C# (espejo) | **SQLite** (`Microsoft.Data.Sqlite`) | misma clave `db.url` |

- Los `.db` locales son intercambiables a nivel de datos (mismo esquema) y siempre se
  ejecutan las queries de `HISTORIAL_LOCAL.md`, nunca SQL ad-hoc por cliente.
- Esquema creado al primer arranque (sin Flyway en cliente) ejecutando `schema-local.sql`.
- `client.properties` es **el mismo archivo de formato** para los 3 lenguajes
  (`host`, `puerto`, `db.url`, `idioma`).

### 5.3 Estructuras de datos en memoria (requisito: "una o más estructuras")

```
GestorColasDeMensajes : BlockingQueue<Mensaje> colaProcesamiento + ConcurrentHashMap<Long, Mensaje> indice
ConexionesClientes    : ConcurrentHashMap<Codigo, Set<IdSesion>> sesionesVivas   ← SOLO memoria
Usuarios              : ConcurrentHashMap<Codigo, UsuarioDTO> directorio          ← sembrado por Fabrica (§2.5 regla 6)
```

- Los mensajes **se consultan desde las estructuras** (cola + índice) y `Mensajes` los
  **persiste llamando a `AlmacenarInformacion`** (como en el diagrama) → MySQL/disco, en el hilo
  de la tubería (nunca en el hilo TCP). `LogDeEventos` persiste solo eventos y auditoría
  (`registro_acciones`, `conexiones`), no mensajes.
- `ConexionesClientes` **nunca escribe en disco ni en MySQL**; solo informa estados
  vivos a la fachada (informe de conectados en caliente).
- Al reiniciar, `FachadaDeServicios.iniciar()` rehidrata `GestorColasDeMensajes` con los mensajes que le entrega `Mensajes` (que los lee de `AlmacenarInformacion`).

### 5.4 Adapter — conmutación de BD (multi-motor heredado de MENSAJERIA)

> **Patrón Adapter** para cambiar de base de datos del servidor cuando se quiera,
> SIN tocar código: mismo `.jar`, solo cambia configuración. MySQL es el motor
> **principal** (exigido por `REQ.md`); PostgreSQL y Oracle quedan como
> **opcionales conmutables** (base MENSAJERIA ya los trae).

**Puerto (`PersistenciaPort`, definido en el paquete de `AlmacenarInformacion`; los adaptadores de `persistencia` lo implementan → dependencia INVERTIDA, E15 en §2.5):**

```java
public interface PersistenciaPort {           // AlmacenarInformacion depende SOLO de esto
    <T extends Record> T guardar(T dto);          // habla en DTO-Records, nunca en entidades JPA
    Optional<MensajeDTO> buscarMensajePorId(long id);
    List<MensajeDTO> paginaConversacion(...);
    // ... operaciones de guardado/consulta del núcleo
}
```

**Adaptadores:**

| Adaptador | Rol |
|---|---|
| `JpaPersistenciaAdapter` (implementa `PersistenciaPort`) | Único adaptador de escritorio; JPA + Hibernate traduce JPQL al dialecto del motor (esto ya ES un Adapter de dialectos) |
| `FlywayMigrationAdapter` | Localiza `db/migration/{mysql,postgres,oracle}/V1__init.sql` según `db.active` |
| `DataSourceAdapter` (`DataSourceFlywayConfig`, heredado) | Construye Hikari/`DriverManager` + driver/dialecto por vendor |

**Matriz de conmutación (mismo `.jar`, solo properties):**

| Clave | MySQL (principal) | PostgreSQL | Oracle |
|---|---|---|---|
| `db.active` | `mysql` | `postgres` | `oracle` |
| `db.<motor>.url` | `jdbc:mysql://localhost:3307/mensajeria_db?...` | `jdbc:postgresql://localhost:5432/mensajeria_db` | `jdbc:oracle:thin:@//localhost:1521/FREEPDB1` |
| `db.<motor>.user/pass` | `root/…` | `postgres/…` | `MENSAJERIA_DB/…` |
| `flyway.<motor>.locations` | `classpath:db/migration/mysql` | `…/postgres` | `…/oracle` |
| Contenedor | `docker compose up mysql` | `… postgres` | `… oracle` (los 3 ya en `docker-compose.yml`) |

**Reglas que hacen que la conmutación sea "solo config":**
- **Cero SQL nativo** en el código: solo JPQL y derived queries de Spring Data →
  Hibernate conmuta el dialecto automáticamente.
- `spring.jpa.hibernate.ddl-auto=none` siempre → el esquema SOLO lo define Flyway
  (migración por motor).
- Ninguna clase fuera de `persistencia/` conoce `DataSource`, `EntityManager` ni
  rutas Flyway (cumple §11: solo `persistencia` conoce JPA; sus entidades viven en
  `persistencia.entidades` y se mapean a/desde DTO-Records).
- Lado cliente (§5.2): sin cambios — `InterfazHistorialLocal` ya es el puerto y
  H2/SQLite sus adaptadores (mismo patrón en miniatura).

---

## 6. Servidor — Funciones del `REQ.md`

| # | Requisito | Implementación |
|---|---|---|
| 1 | Validación código+contraseña | `Usuarios.validarCredenciales` (BCrypt Java puro), orquestado por la Fachada; alta de usuarios **solo** por siembra `usuarios_iniciales.csv` (§5.1, P2) |
| 2 | Restringir usuarios conectados (config) | `server.properties → max_conexiones` (cuenta sesiones activas, §4.3) + `Servicios.verificarLimiteConexiones` (la Fachada consulta `ConexionesClientes`) |
| 3 | Logs en DB relacional + directorio | `registro_acciones` (MySQL) + `uploads/` |
| 4 | MySQL por archivos de configuración | `application.properties` + Hikari + Flyway (+ conmutación §5.4) |
| 5 | Mensajes en estructuras de datos | §5.3 (cola + índice + ConcurrentHashMap) |
| 6 | Procesar TEXTO (hash/chars/palabras) | `Mensajes` → tubería texto §3.2 (archivos genéricos: `MENSAJE_ARCHIVO`, §3.2) |
| 7 | Procesar IMAGEN (hash + 5 filtros) | `Mensajes` → tubería imagen §3.3 (cadena secuencial, sin modo alterno) |
| 8 | Arquitectura según diagrama | §2 |
| 9 | Enviar a todos (broadcast) | `BroadcastCommand` |
| 10 | Usuario multi-conexión | `Set<SesionCliente>` por código |
| 11 | Cerrar conexiones | `CLOSE_NOTICE` + `CierreCommand` + cierre por **timeout 600 s** (§5.1, P3) |
| 12 | Info de cada mensaje en consola | `VistaConsola` suscrita vía `Fachada.suscribirEventos` a `LogDeEventos`; escritorio con **auto-refresco** (Fase 10.4, P1) |
| 13 | Informes | §8 → **Fase 9 ✅** (CLI `informe …` + pestaña Informes) |

---

## 7. Cliente — Funciones

| # | Requisito | Implementación |
|---|---|---|
| 1 | Ver usuarios (todos, con estado conectado/desconectado) | `LISTAR_USUARIOS` → panel `TableView` (refresco periódico; filtro «solo conectados») |
| 2 | Mensajes con usuario específico (texto/imagen/archivo) | Chat 1-a-1 en `ChatController` + `ImageView` preview + adjuntar archivo (`MENSAJE_ARCHIVO`) y descarga |
| 3 | Base local multilenguaje con configuración | `client.properties` + §5.2 (`schema-local.sql` + `HISTORIAL_LOCAL.md`) |
| 4 | **Aplicación de escritorio con capas y componentes** | **§7.1** (capas cliente Java; Python espejo en §7.2) |
| 5 | **Clientes en lenguajes ≠ Java** | **§7.2** (Python) + **§7.3** (C#) sobre el wire protocol JSON de §4.0 |
| 6 | TCP-IP | `ConexionCliente` (Socket 5000, tramas JSON) |
| 7 | DIP, DI, Fábricas | §9 |

### 7.1 Cliente Java (PRINCIPAL) — capas y componentes

```
presentacion/        LoginView, RegistroView [oculta: registro solo CSV, P2], ChatView, UserListView (FXML)
                     + celdas UsuarioCell (presencia ●/○ + badge no leídos) y MensajeCell (✓/✓✓/✓✓ cyan)
        │ solo
        ▼
FachadaCliente       ← único punto de entrada de la UI (patrón Facade, espejo del servidor)
        │ inyecta interfaces, nunca impls (DIP + DI constructor)
        ▼
componentes/
  ├─ InterfazAutenticacion   → Autenticacion (login/sesión; registro deshabilitado, P2)
  ├─ InterfazConexionRed     → ConexionCliente (socket + TramaCodec JSON + demultiplexor id/futuros)
  ├─ InterfazHistorialLocal  → HistorialLocal (DAO H2: mensajes + recientes + pendientes)
  └─ InterfazCacheUsuarios   → CacheUsuarios (DAO H2)
        ▼
persistencia/        H2 (config `client.properties`)   [accesible SOLO vía sus DAOs]
transversal/         records/ (EstadoMensaje, RecienteChat), utilerias/ (preview, validaciones), config/
```

Regla: los controllers **solo** llaman a `FachadaCliente`; ninguna UI abre sockets
ni toca H2 directamente.

**UX de chat (Fases 5–6 + 10.3):** un **único botón «Enviar»** (P6) que manda texto,
imagen, o ambos (vista previa por `📷 Adjuntar`/drag&drop, permitido solo-imagen);
búsqueda + filtros `Todos/Conectados/No leídos`; lista de conversaciones **recientes**
ordenada por actividad con restauración del último chat (`Preferences`); paginación
`↑ Cargar anteriores`; presencia en vivo (push `PRESENCIA` + sondeo 5 s) y checks de
estado `ENVIADO→ENTREGADO→LEIDO` (§4.0).

**Módulos Maven del cliente Java:** igual que el servidor, `client-interfaces` (componente
«Interfaces» del cliente: `InterfazAutenticacion`, `InterfazConexionRed`, `InterfazHistorialLocal`,
`InterfazCacheUsuarios`; sin dependencias) y `client-app` (resto de capas en paquetes). Los espejos
Python y C# reflejan `client-interfaces` como paquete/namespace propio.

### 7.2 Cliente Python (ESPEJO funcional) — mismas capas, PySide6 + QFluentWidgets

> Objetivo único y explícito: **probar que el protocolo es multilenguaje (C5)**.
> Alcance: las mismas funciones del §7 (login, lista de usuarios (con estado), chat 1-a-1
> texto/imagen/archivo con preview, historial paginado, descarga). No replica informes.

```
client-python/
├── ui/                 ← PySide6 + QFluentWidgets: LoginWindow, ChatWindow
│                          (señales Qt para eventos de red; Pillow para preview)
├── fachada/            ← FachadaCliente (mismo patrón, mismos nombres de operación)
├── componentes/
│   ├─ autenticacion.py
│   ├─ conexion_red.py  ← socket + struct + json (stdlib) → mismo PROTOCOLO.md
│   └─ historial_local.py
├── store/              ← sqlite3 stdlib (schema idéntico a H2: historial_local, cache)
├── client.properties   ← MISMO formato que el Java (host, puerto, ruta DB)
├── requirements.txt    ← Pillow + PySide6 + QFluentWidgets (versiones fijadas)
└── main.py
```

- **Estructura espejo 1-a-1** con §7.1: presentación → fachada → componentes →
  persistencia → transversal (aplicando SOLID y capas también en Python).
- El servidor **no sabe** que el peer es Python: solo ve tramas JSON válidas.
- Diferencia deliberada: persistencia local SQLite (stdlib) en vez de H2 — el
  contrato entre capas es el mismo, cambia el motor (y demuestra que la capa
  persistencia es intercambiable → DIP).

### 7.3 Cliente C# / .NET (ESPEJO funcional) — mismas capas, WPF + Sqlite

> Tercera vista de escritorio en **otro lenguaje**. Mismo objetivo que §7.2: reforzar
> C5 con un lenguaje de runtime distinto (.NET). Alcance espejo completo: login,
> lista de usuarios (con estado), chat 1-a-1 texto/imagen/archivo con preview, historial paginado, descarga.

- **Stack**: .NET 8 + **WPF (XAML)** — espejo natural de JavaFX/FXML (controllers ≈ MVVM),
  `System.Net.Sockets` + `System.Text.Json` para el wire protocol, `Microsoft.Data.Sqlite`
  para el store local.
- **Estructura espejo 1-a-1** con §7.1:

```
client-dotnet/
├── Views/                ← XAML: LoginWindow, ChatWindow, UserListPane
│                           (preview imágenes con BitmapImage, decodificación Base64)
├── Fachada/              ← FachadaCliente (mismas operaciones que Java/Python)
├── Componentes/          ← Autenticacion, ConexionRed (TramaCodec JSON),
│                            HistorialLocal (queries de HISTORIAL_LOCAL.md)
├── Store/                ← Microsoft.Data.Sqlite + schema-local.sql
├── client.properties     ← MISMO formato (host, puerto, db.url, idioma)
└── Mensajeria.csproj     ← .NET 8, WPF
```

- Ejecuta **las mismas queries** de `common-protocol/HISTORIAL_LOCAL.md` y las
  mismas tramas de `PROTOCOLO.md`: el servidor no distingue C# de Java/Python.

---

## 8. Informes (servidor) — base de la **Fase 9** `[RF-S10]`

Todos se piden a la Fachada, que reúne los datos por sus flechas del diagrama y le pasa los DTOs a
`Servicios` para armar el informe (lógica pura, sin tocar BD):

| # | Informe | Datos | Origen (flechas) |
|---|---|---|---|
| 1 | **Directorio de usuarios registrados** | código, nombres, apellidos, programa, fecha de registro, última conexión | `Usuarios.listar()` (memoria, §2.5 regla 6) + `AlmacenarInformacion` |
| 2 | **Conectados y frecuencia de acceso** | `COUNT(*) GROUP BY usuario` (veces conectado), sesiones vivas, tiempos | `LogDeEventos.consultarConexiones()` → `conexiones` (JPQL `GROUP BY`, no cálculo en memoria) + `ConexionesClientes` (activas en caliente) |
| 3 | **Histórico cronológico de mensajes** | remitente, destinatario, fecha/hora, tipo, métricas (SHA-256, chars, palabras) o rutas de las 5 derivadas + ms por etapa | `Mensajes.detalle(...)` → `mensajes` con `JOIN FETCH` a archivos/métricas + `etapas_filtrado` (JSON) |
| 4 | **Bitácora de auditoría** | eventos críticos (`LOGIN`, `LOGOUT`, `KICK`, `TIMEOUT`, errores) consolidando DB + log plano | `LogDeEventos.consultar(...)` → `registro_acciones` + `logs/server.log` |

Presentación:
- **VistaConsola (CLI)**: subcomandos `informe usuarios|conexiones|mensajes|auditoria` con
  salida en tablas ASCII (solo llama a `Fachada`).
- **VistaEscritorio (JavaFX)**: pestaña **Informes** con `TableView`, filtro por rango de
  fechas y código de usuario, y exportación a CSV (solo llama a `Fachada`).

Ambas consumen el contrato `Fachada` (sin lógica en la UI). Verificación: criterio 6 y 22 (§13).

---

## 9. SOLID, Patrones de Diseño y Clean Code

### SOLID

| Principio | Aplicación concreta |
|---|---|
| **S**RP | Un handler = un comando; un filtro = una transformación; un caso de uso de la Fachada = una operación; la Fachada coordina, no implementa; la UI no persiste; solo `AlmacenarInformacion` toca `Persistencia`. |
| **OCP** | Nueva etapa de tubería o nuevo comando TCP = nueva clase registrada en su `Map`, sin modificar `ProtocoloClienteServidor` ni `Tuberia`. Otra implementación de un componente = otro `.jar` en el classpath (`ServiceLoader`), sin tocar `servidor-app`. |
| **LSP** | Cualquier `Filtro` sustituye a otro; cualquier implementación de `InterfazProtocoloComunicacion` (TCP, doble de pruebas) sustituye a otra sin que la Fachada lo note. |
| **ISP** | Interfaces pequeñas por rol: las 5 «Interfaz…» (una por módulo), los callbacks `ReceptorDeTramas` y `ConsumidorDeMensajes`, los roles `SalidaAutenticacion`/`SalidaMensajeria`/`SalidaConsultas`/`SalidaRespuestas`, y las interfaces de paquete `InterfazMensajes`, `InterfazLogDeEventos`, `InterfazConexionesClientes`, `InterfazAlmacenarInformacion` y `PersistenciaPort`. |
| **DIP** | En todas las fronteras (§2.5): (1) cada `comp-*` depende solo de su `int-*`, y `servidor-app` compila solo contra los `int-*`; (2) `FachadaDeServicios` inyecta solo abstracciones; (3) lo que un componente necesita lo declara como callback en su propio `int-*` y lo implementa la Fachada; (4) `persistencia` depende del `PersistenciaPort` de `AlmacenarInformacion` (flecha invertida); (5) las vistas dependen del contrato `Fachada`; (6) solo `Fabrica` conoce implementaciones, y las descubre con `ServiceLoader`. |

### Patrones de diseño

| Patrón | Dónde |
|---|---|
| **Facade + Mediator** | `FachadaDeServicios`: única puerta de la UI y único nodo que conecta los componentes entre sí |
| **Pipes & Filters** | Tuberías (`Filtro`, `Tuberia`, `ContextoTuberia`) invocadas por **`Mensajes`**; filtros en **`UtileriasVarias`** ← *requisito central* |
| **Strategy** | Cada `Filtro` de `UtileriasVarias` (hash, grises, sepia, giro, brillo, reducción, conteos), todos sin estado |
| **Command** | `Map<String, CommandHandler>` dentro de `ProtocoloClienteServidor` |
| **Factory / Service Provider** | `Fabrica` (raíz de composición) + SPI `Proveedor<Nombre>` de cada `int-*` cargado con `ServiceLoader`; `TuberiaFactory`; `MensajeFactory` |
| **Builder** | Construcción de `Mensaje` y pipelines configurables |
| **Observer** | `LogDeEventos` (consola y DB suscritas vía `Fachada.suscribirEventos`) y oyente de etapas de las tuberías |
| **Repository** | JPA repos (MySQL servidor), DAOs locales H2/SQLite (clientes) |
| **Adapter** | **Conmutación de BD (§5.4)**: `PersistenciaPort` → `JpaPersistenciaAdapter` + `FlywayMigrationAdapter` + `DataSourceAdapter` · `InterfazHistorialLocal` → adaptadores H2 (Java) / SQLite (Python, C#) |
| **Ports & Adapters** | Los callbacks de cada `int-*` (`ReceptorDeTramas`, `SalidaClienteServidor`, `ConsumidorDeMensajes`) son puertos que implementa la Fachada; `PersistenciaPort` lo implementa `persistencia`; rompen los ciclos sin flechas nuevas |
| **DTO / Record** | Módulo `dto` (DTO-Records del diagrama): Records del wire + Records internos |
| **Object Pool** | Exigido por `[RNF-04]` (P4). Tres pools reales, todos acotados y por configuración: (1) **red** = `ThreadPoolExecutor` propio de `comp-protocolo-comunicacion` (`red.pool.core=10 / max=50 / queue=100`, `AbortPolicy`); (2) **filtros** = `ThreadPoolExecutor` en `Fabrica` (`filtros.hilos=4`, `CallerRunsPolicy`), apagado en `shutdown`; (3) **conexiones DB** = HikariCP (`pool.maximumPoolSize=10`) con conmutación a `DriverManagerDataSource` si `pool.enabled=false`. **No** existe `ConexionPool` didáctico de MENSAJERIA (eliminado, §10) |
| **Singleton** | Únicamente los beans de configuración (`@ConfigurationProperties`) |

### Clean Code

- Nombres de dominio en español del negocio: `CodigoEstudiantil`, `ProgramaAcademico`,
  `ProcesarImagenCommand` (sin `manager/util2/dataTmp`).
- Métodos cortos (<30 líneas), sin anidamiento > 2, sin `boolean` mágico
  (usar enums `EstadoConexion`).
- Sin hardcode: `localhost:5000`, `9090`, credenciales → todo por configuración.
- `Optional` en búsquedas; excepciones de dominio (`UsuarioNoEncontradoException`).
- Comentarios solo en contratos públicos y decisiones *por qué*.
- Verificación: duplicación < 3 % y cobertura de `transversal/utilerias/` > 80 %.

---

## 10. Análisis de MENSAJERIA --main (base híbrida)

### ✅ Se REUTILIZA (≈70 %)

- `pom.xml` (SpringBoot/JPA/Flyway/Lombok, ahora confinado a `servidor-app`) y `application.properties` multi-motor.
- Config: `DbProperties`, `PoolProperties`, `FlywayGroupProperties`,
  `DataSourceFlywayConfig`, `PasswordConfig` (BCrypt), `ThreadPoolConfig`.
- Entidades `Usuario`, `Conexion`, `SesionActiva`, `ConfiguracionLimites` + repositories (confinados
  en `persistencia.entidades`) y la lógica de `UsuarioService`, `LoginService`, `ConexionService`,
  `SesionActivaService`, `ArranqueSyncService`, que migra a `Usuarios`, `Servicios`, `ConexionesClientes` y `Fabrica`.
- Auditoría: `SistemaObserver`, `RegistroAccionObserver`, `TipoAccion`.
- Red: `ServidorTCP`, `LoggerServidor`, `ConexionCliente`, `DetectorIP`
  (el DTO `Shared/Mensaje` se **reescribe** como Record JSON, ver §4.0).
- Cliente: `LoginController`, `RegistroController` (⚠️ **oculto**: registro solo CSV, P2),
  `ChatController`,
  `UserListController`, `ValidacionUtils`, `AlertUtils`.
- Flyway `V1__init.sql` (7 tablas) + `docker-compose.yml` (MySQL 3307).

### 🔧 Se MODIFICA

| Archivo | Cambio |
|---|---|
| `ClienteHandler` | God-class → transporte/tramas en `ProtocoloComunicacion` + `Map<String, CommandHandler>` en `ProtocoloClienteServidor` |
| `ServidorTCP` | Respetar parámetro `puerto`; mapa de conexiones inyectado (no `static`); pool acotado por config |
| `MensajeriaService` | Su lógica migra a **`Mensajes`** (encolar en `GestorColasDeMensajes` + tuberías + `AlmacenarInformacion`) |
| `MensajeFactory` | Soportar `hash_sha256`, `num_caracteres`, `num_palabras`, `etapas_filtrado` |
| `ArchivoService` | Ejecutar la tubería de imagen (5 derivados) vía `Mensajes` en subida de imagen |
| `LoginService`/`ConexionService` | Unificar criterio de límites (solo conexiones **activas**, no históricas) |
| `RegistroAccionObserver` | No acoplar a `DashboardController` (usar evento + SLF4J) |
| `InformesDesktopController` | Usar queries de repositorio (`GROUP BY`), no cálculo en memoria |
| `ChatController` | Preview `ImageView` para imágenes + paginación de historial |
| `ServerApplication` | Separar chequeo de puerto del boot Spring/FX; quitar `9090` hardcodeado |
| Config Spring (`ThreadPoolConfig`, beans de servicios) | Sale de los componentes; Spring queda solo para persistencia en `servidor-app` y la DI pasa a `Fabrica` |
| `SecurityPermitAllConfig` | Restringir al mínimo necesario |
| `LoggerServidor` | `FileWriter` por línea → Logback (`logs/server.log`) |
| `UserListView/UserListPanel` | Eliminar FXML duplicado |

### 🆕 Se CREA

1. **`common-protocol`**: módulo `dto` (Records JSON: es el DTO-Records del diagrama) + `codec/` (Gson) +
   catálogo de tipos + **`PROTOCOLO.md`** (wire protocol multilenguaje, §4.0).
2. Módulo **`utilerias`** (**UtileriasVarias**): `Filtro`, `Tuberia`, `TuberiaFactory`,
   `ContextoTuberia`, filtros de texto, imagen y archivo (sin estado, `ResultadoFiltro` inmutable) + sus tests.
   **`Mensajes`** los compone.
3. **`server.properties`** (max_conexiones, puerto, uploadDir) y
   **`client.properties`** (H2, host/puerto).
4. Seed **`usuarios_iniciales.csv`** + importador en el arranque de `Fabrica`.
5. Cliente local: **`schema-local.sql`** + **`HISTORIAL_LOCAL.md`** + DAOs
   (`HistorialLocalDao`, `CacheUsuariosDao`) con motor nativo por lenguaje (§5.2).
6. `BroadcastCommand`, `HistorialCommand` paginado, `CierreCommand`/`CLOSE_NOTICE`.
7. `GestorColasDeMensajes` real (BlockingQueue + índice).
8. VistaConsola de informes (texto CLI).
9. **Clientes espejo**: Python (PySide6 + QFluentWidgets + Pillow + sqlite3) y C# (WPF +
   Microsoft.Data.Sqlite) con capas idénticas a §7.2/§7.3, sobre `schema-local.sql`
   + `HISTORIAL_LOCAL.md`.
10. Prueba cruzada Java↔Python↔C# documentada (§13.8).
11. Estructura **multi-módulo Maven** del servidor (§2.4): 10 componentes independientes (5 `int-*` + 5 `comp-*`), `dto` y `utilerias`.
12. **`Fabrica`** (dentro de `InicioServidor`; carga los `comp-*` con `ServiceLoader`) y los puertos de salida (callbacks) de cada `int-*`.
13. Reglas ArchUnit / Maven Enforcer contra ciclos y dependencias prohibidas, y `DiagramaFidelidadTest` (§13.16).

### ❌ Se ELIMINA

- Controladores web Thymeleaf (`Controladores/Web`, `templates/`), REST
  (`ApiUsuarioController`), `WebSocketConfig`.
- `ConexionPool` didáctico (usar solo Hikari).
- Dependencias UI no usadas: `fxgl`, `tilesfx`, `controlsfx`, `formsfx`,
  `validatorfx`, `bootstrapfx`, `javafx-web/media/swing` (si no se usan).
- Devolución de `byte[]` completo en `HISTORIAL_RESPUESTA` (→ paginado + descarga).

---

## 11. Estructura de carpetas final

```
Arquitectura cliente servidor/     ← raíz de documentación y guías de despliegue
├── README.md                      ← índice y visión general
├── DESPLIEGUE.md                  ← topología y asignación a computadores
├── REQUERIMIENTOS.md              ← contrato funcional vigente
├── PLAN.md                        ← este documento: arquitectura y decisiones
├── ESTADO_ROADMAP.md              ← estado breve y backlog
├── GUIA_ACEPTACION.md             ← pruebas automatizadas y smoke integrado
└── universidad-mensajeria/
├── common-protocol/               ← CONTRATO MULTILENGUAJE (§4.0 + §5.2)
│   ├── PROTOCOLO.md               ← tramas, catálogo de tipos, ejemplos, errores
│   ├── HISTORIAL_LOCAL.md         ← queries canónicas del historial local (§5.2)
│   ├── schema-local.sql           ← esquema ÚNICO dialecto-neutro (TEXT/INTEGER/REAL/BLOB)
│   ├── dto/                       ← módulo `dto` = «DTO-Records» del diagrama. Paquetes `protocolo/` (Records JSON del
│   │                                 wire: Mensaje, LoginReq/Resp...) e `interno/` (IdSesion, EventoDTO, InformeDTO,
│   │                                 LimitesDTO, excepciones de dominio). Lo usan servidor y cliente Java. Depende de nada
│   └── codec/                     ← TramaCodec (4B len + JSON UTF-8), implementación Java; depende de `dto`
├── server/                        ← proyecto Maven PADRE (multi-módulo, §2.4; incluye ../common-protocol/dto y /codec)
│   ├── pom.xml                    ← módulos + reglas Enforcer/ArchUnit (sin ciclos)
│   ├── utilerias/                 ← «UtileriasVarias»: Filtro, Tuberia, TuberiaFactory, ContextoTuberia,
│   │                                 filtros texto (SHA256/chars/words) e imagen (5 transformaciones),
│   │                                 ResultadoFiltro/Metadatos inmutables, FiltroMain (opcional, §3.6)
│   ├── int-protocolo-comunicacion/     ← «InterfazProtocoloComunicacion» (interfaz + ReceptorDeTramas + Proveedor)
│   ├── int-cliente-servidor/           ← «InterfazClienteServidor» (interfaz + SalidaClienteServidor + Proveedor)
│   ├── int-servicios-disponibles/      ← «InterfazServiciosDisponibles»
│   ├── int-usuarios-disponibles/       ← «InterfazUsuariosDisponibles»
│   ├── int-gestor-mensajes/            ← «InterfazGestorMensajes» (interfaz + ConsumidorDeMensajes + Proveedor)
│   ├── comp-protocolo-comunicacion/    ← «ProtocoloComunicacion»: ServidorTCP + ClienteHandler + TramaCodec
│   ├── comp-protocolo-clienteservidor/ ← «ProtocoloClienteServidor»: CommandHandler + commands/
│   ├── comp-servicios/            ← «Servicios»
│   ├── comp-usuarios/             ← «Usuarios»
│   ├── comp-gestor-colas/         ← «GestorColasDeMensajes»
│   └── servidor-app/              ← ensambla todo (jar-with-dependencies + metaInf-services)
│       └── src/main/java/universidad/mensajeria/server/
│           ├── inicio/             ← InicioServidor + Fabrica (ÚNICO lugar que construye; comp-* vía ServiceLoader) + lectura de config + SemillaUsuarios (CSV, P2)
│           ├── vistaconsola/       ← VistaConsola (CLI: `estado/conectados/usuarios/cola/informe …`; eventos push en vivo)
│           ├── vistaescritorio/    ← VistaEscritorio (JavaFX: pestañas Estado/Usuarios/Conectados/Eventos/Informes; auto-refresco §12 F10.4)
│           ├── fachadadeservicios/ ← FachadaDeServicios (implementa `Fachada`; mediador) + casosdeuso/ (una clase por operación)
│           ├── mensajes/           ← InterfazMensajes + Mensajes (único que invoca las tuberías)
│           ├── logdeeventos/       ← InterfazLogDeEventos + LogDeEventos (Observer)
│           ├── conexionesclientes/ ← InterfazConexionesClientes + ConexionesClientes (SOLO memoria)
│           ├── almacenarinformacion/ ← InterfazAlmacenarInformacion + AlmacenarInformacion + PersistenciaPort
│           │                         (ÚNICO punto que toca Persistencia)
│           ├── persistencia/       ← entidades/, repositorio/, adaptadores de PersistenciaPort  [E15 por puerto]
│           └── transversal/{fachada,config}/ ← contrato `Fachada` + records de config (RedProperties, SesionProperties…)
│       └── src/main/resources/    ← application.properties, server.properties, db/migration, usuarios_iniciales.csv
│   ⚠️ NO existen paquetes `presentacion/`, `fachada/` ni `nucleo/`: los nombres espejo el diagrama
│      y el `DiagramaFidelidadTest` (§13.16) los exige así.
├── client/                        ← CLIENTE JAVA (PRINCIPAL, Maven multi-módulo, capas §7.1)
│   ├── pom.xml                    ← padre
│   ├── client-interfaces/         ← módulo «Interfaces»: Interfaz Autenticacion/ConexionRed/HistorialLocal/CacheUsuarios
│   │                                  + records compartidos (EstadoMensaje, RecienteChat)
│   └── client-app/src/main/java/  ← resto de capas, en paquetes:
│       ├── presentacion/          ← ClienteApp, LoginController, ChatController (+ celdas/ UsuarioCell, MensajeCell);
│       │                              RegistroController+registro.fxml OCULTOS (registro solo CSV, P2)
│       ├── fachada/               ← FachadaCliente (las UI solo llaman aquí)
│       ├── componentes/           ← autenticacion/, conexion/, historial/, cache/ (interfaces en client-interfaces)
│       ├── persistencia/          ← H2 (historial_local, cache, pendientes_envio) [solo vía componentes]
│       └── transversal/           ← config/ (ClienteConfig), utilerias/ (ValidacionCliente, preview)
│   └── client-app/src/main/resources/vistas/ ← login.fxml, chat.fxml, registro.fxml + estilos/chat.css
├── client-python/                 ← CLIENTE PYTHON (ESPEJO, capas §7.2)
│   ├── ui/                        ← PySide6 + QFluentWidgets (eventos por señales Qt)
│   ├── fachada/                   ← FachadaCliente
│   ├── componentes/               ← autenticacion.py, conexion_red.py (struct+json),
│   │                                  historial_local.py
│   ├── store/                     ← sqlite3 (schema-local.sql + HISTORIAL_LOCAL.md)
│   ├── client.properties          ← mismo formato que el Java
│   ├── requirements.txt           ← Pillow + PySide6 + QFluentWidgets
│   └── main.py
└── client-dotnet/                 ← CLIENTE C# (ESPEJO, capas §7.3)
    ├── Views/                     ← XAML: LoginWindow, ChatWindow, UserListPane
    ├── Fachada/                   ← FachadaCliente
    ├── Componentes/               ← Autenticacion.cs, ConexionRed.cs (TramaCodec JSON),
    │                                  HistorialLocal.cs (HISTORIAL_LOCAL.md)
    ├── Store/                     ← Microsoft.Data.Sqlite + schema-local.sql
    ├── client.properties          ← mismo formato que Java/Python
    └── Mensajeria.csproj          ← .NET 8 + WPF
```

### Regla de dependencias (verificable)

**Servidor, entre módulos Maven (compilación):**

```
dto                     → nada
utilerias               → dto
int-*  (×5)             → dto
comp-* (×5)             → su int-* + dto   (comp-protocolo-comunicacion además → codec)
                          NUNCA: otro comp-*, otro int-*, utilerias, servidor-app
servidor-app            → 5 int-* + dto + utilerias   (los 5 comp-* SOLO con scope runtime)
```

**Servidor, dentro de `servidor-app` (flechas del diagrama y su forma en compilación):**

```
inicio.InicioServidor      → vistaconsola.VistaConsola, vistaescritorio.VistaEscritorio   [E1, E2]
inicio.Fabrica             → todo (único autorizado a conocer implementaciones; comp-* solo por ServiceLoader)
vistaconsola/vistaescritorio → transversal.fachada (contrato Fachada)                     [E3, E4]
fachadadeservicios         → 5 int-* + interfaces de mensajes / logdeeventos / conexionesclientes  [E5–E12]
mensajes                   → almacenarinformacion (InterfazAlmacenarInformacion) + utilerias [E13]
logdeeventos               → almacenarinformacion (InterfazAlmacenarInformacion)          [E14]
conexionesclientes         → nada                                              (sin flechas salientes)
almacenarinformacion       → PersistenciaPort (de su propio paquete)           [E15, por puerto]
persistencia               → PersistenciaPort (implementa: INVERTIDA en compilación) + BD  [E16]
dto                        ← usado por todos;  utilerias ← solo mensajes
```

Cualquier dependencia que no esté en estas listas rompe el build (§13.16).

**`client`, `client-python` y `client-dotnet` (una sola aplicación por capas):**

```
presentacion/ui → fachada         (nada más)
fachada         → client-interfaces / componentes.*.Interfaz*   (interfaces, nunca impl)
componentes     → dominio + transversal + persistencia vía su DAO
persistencia    → (nada: hoja)
```

---

## 12. Fases de ejecución (orden)

| Fase | Contenido | Entregable | Estado |
|---|---|---|---|
| **0** | Esqueleto Maven **multi-módulo** (§2.4: `dto`, `utilerias`, 5 `int-*`, 5 `comp-*`, `servidor-app`) + `Fabrica` (`ServiceLoader`) + reglas ArchUnit/Enforcer + `DiagramaFidelidadTest` + `common-protocol` (wire JSON §4.0 + `PROTOCOLO.md` + codec) + configs + Flyway/H2 + seed CSV. **Adapter §5.4**: `PersistenciaPort` + verificar arranque contra los 3 motores (`db.active`) | Arranca vacío en MySQL/Postgres/Oracle; build sin ciclos | ✅ |
| **1** | Refactor red: transporte/tramas en `comp-protocolo-comunicacion`; Commands + sesiones en `comp-protocolo-clienteservidor` (callbacks `SalidaClienteServidor` / `ReceptorDeTramas`, implementados por la Fachada) + pool acotado + mapa inyectado | Login/listar funcionando | ✅ |
| **2** | Framework `Filtro`/`Tuberia`/`TuberiaFactory` + filtros texto/imagen/archivo (cadena secuencial, sin estado) + **tests** | `mvn test` verde | ✅ |
| **3** | Tuberías (`Mensajes` + `UtileriasVarias`, una cadena por mensaje en el pool `filtros.hilos`) + `AlmacenarInformacion` (DB + promoción `work/` → `uploads/`) + logs | Mensajes con hash/conteos/5 imágenes | ✅ |
| **4** | Broadcast, multi-conexión, cierre, límites por config, kick, dedup global de `archivos` | Broadcast verificado | ✅ |
| **5** | Cliente: lista de usuarios (con estado), chat 1-a-1 con preview, historial local (H2), **UX Fase 6** (burbujas, búsqueda/filtros, recientes + restaurar, drag&drop solo imágenes con thumbnail) | Chat completo offline/online | ✅ |
| **6** | Historial paginado (`HISTORIAL_REQ/PAGE` + descarga + `SYNC_LOGIN`), presencia push (`PRESENCIA`), estado de entrega (`MENSAJE_ENTREGADO`/`MENSAJE_LEIDO` + checks ✓/✓✓), paginación local ↑, multi-sesión (`KICK` otras / Salir en todos) | Chat persistente sin `byte[]` masivos | ✅ |
| **7** | **Cliente Python espejo** (iniciado con tkinter; UI actual PySide6 + QFluentWidgets tras 14.2; Pillow + sqlite3 y mismas capas §7.2) sobre `PROTOCOLO.md` | Login/chat Java↔Python funcionando | ✅ |
| **8** | **Cliente C# espejo** (WPF + Microsoft.Data.Sqlite, mismas capas §7.3) | Conversación a 3 bandas Java↔Python↔C# | ✅ |
| **9** | **Informes administrativos `[RF-S10]`** (§8): 9.1 los 4 informes como DTOs puros en `comp-servicios`/casos de uso (queries `GROUP BY`/`JOIN FETCH` vía `AlmacenarInformacion`); 9.2 comandos CLI `informe usuarios\|conexiones\|mensajes\|auditoria` en `VistaConsola` (tablas ASCII); 9.3 pestaña **Informes** en `VistaEscritorio` (`TableView` + filtro fechas/código + export CSV); 9.4 tests de los 4 informes contra fixture = datos DB | 4 informes en consola + escritorio | ✅ |
| **10** | **Endurecimiento y cierre** (pruebas P1–P6, P7): 10.1 timeout inactividad **600 s** (P3) + test; 10.2 **registro solo CSV** (P2, eliminado en Fase 18): sin `RegistroCommand`/`RegistrarCaso`/flag; un `REGISTRO` por red responde `ERROR`; sin `RegistroView` en los 3 clientes, `GUIA_ACEPTACION` actualizado; 10.3 **botón único «Enviar»** (P6): fusiona texto/imagen/ambos, `Adjuntar` solo preview, limpia campo+preview; 10.4 **auto-refresco de vistas** (P1): `Timeline` 5 s en `VistaEscritorio` re-ejecuta estado/usuarios/conectados (eventos ya push en ambas vistas); 10.5 protocolos/documentación consolidados + demo multicliente; 10.6 explicitar **Object Pool** (P4) en §9 | Entrega final verificada (§13.17–22) | ⚠️ docs listas; demo manual pendiente |
| **11** | **Backlog (ex-H2–H5 del ROADMAP, no bloquea la entrega)**, en este orden: **11.4** empaquetado (jpackage/PyInstaller/docker servidor) y proceso de actualización/backup; **11.1** flush de `pendientes_envio` (`[RF-C04]` reintento con worker); **11.2** TLS/SSL + rate-limit + magic-bytes (chunking >16 MiB ya resuelto con `ARCHIVO_INICIO/PARTE/FIN` en Fase 17); **11.3** salas temáticas; **11.5** carga 50–100 clientes + métricas | Evolución post-entrega | ⏳ |
| **12** | **Brechas Ajuste + patrones (P1)**: 12.1 pool real (`PoolDeTrabajadores` + fábrica + acquire/release + "servidor lleno" + RF-S49 + inyección pool red + E2E saturación, sin romper `DiagramaFidelidadTest`); 12.2 consola (menú numerado, ANSI+fallback, paginación, prompt, `mensajesProcesados`); 12.3 `difundir` admin (ext. RF-S08); 12.4 C08 Java+Python, S03 reconciliación; 12.5 espejos (inyección/`IFachadaCliente`, PRESENCIA, re-login, `TipoMensaje.cs`); 12.6 micro-refactors (`InterfazTuberiaFactory`, registro filtros, Javadoc doble raíz); 12.7 `PersistenciaPort` solo DTOs/primitivas | Nuevo spec al 100 % (Ajuste) | ✅ (2026-10-01) |
| **13** | **Rescate cliente Java + cierre X (P0, ejecutada 2026-10-01, tres oleadas)**: 13.1 I/O fuera del hilo FX (`Task` en executor `cliente-fondo`); 13.2 push `PRESENCIA` principal, `Timeline` a respaldo 30 s; 13.3 caché `id→Image` LRU + downsampling 200px + precalentado; 13.4 `ULTIMO_PREVIEW` sin blob + pool H2; 13.5 executor único acotado; 13.6 log `[perf]` en INFO + tests; 13.7 X → `logout()` en Java y `OnClosing` en C#; **13.8 `CASE` sin leer CLOB + 13.9 lista sin blob + `blobPorId` + 13.10 `desuscribir` + 13.11 `[perf] h2/red`; 13.12 "↑" trae páginas remotas (upsert) + 13.13 descarga de imágenes con clic; **13.14 local-primero (red fuera del refresco) + 13.15 `CASE` en página (burbujas con texto) + 13.16 dedup/coalescencia/log selectivo + 13.17 banner + `subs=N` + 13.19 anti-loop acuses + 13.20 eco propio** | Chat rápido estilo WhatsApp | ✅ |
| **14** | **Stacks UI (P2)**: 14.1 Java **AtlantaFX 2.1.0** (tema CSS sobre FXML); 14.2 Python **PySide6 6.11.2 + QFluentWidgets 1.11.3** (reescritura `ui/`, señales Qt); 14.3 C# **WPF UI 4.3.0** (controles y tema Fluent en las vistas WPF existentes). Fachadas, componentes, stores, contrato TCP y llamadas de UI permanecen intactos | UI moderna sin regresión 13.6 | ✅ (2026-10-01) |
| **15** | **Cliente Java tipo Telegram**: nombres y directorio, acuses por ID estable, imágenes diferidas/deduplicadas y `archivo_id`, respuestas citadas, envíos fuera del hilo FX, envío deshabilitado sin contenido y UI sin registro; sin cambios al protocolo. | Cliente principal listo; smoke JavaFX | ✅ código + 53 tests; ⏳ smoke |
| **16** | **Multiinstancia y paridad Python/.NET**: `LOGOUT` local a sesión, presencia al cerrar la última, `KICK` para otras sesiones; datos por cuenta y esquemas locales alineados; UIs nativas conservan su toolkit y el JSON TCP. | Pruebas por cliente; smoke cruzado en seis direcciones | ✅ implementación y suites; ⏳ aceptación manual |
| **17** | **Correcciones por pruebas manuales**: H2/SQLite por cuenta y migración legacy; orientación correcta; informes con nombres y accesos/presencia; imágenes bajo carpeta de propietario; recepción/preview/drag-drop/vistos corregidos en Python/.NET; contrato y documentación de instalación separada. | Java, Python y .NET funcionales; prueba integrada en computadores separados | ✅ código, documentación y suites; ⏳ smoke integrado |
| **18** | **Cierre de brechas**: textos en `uploads/<prop>/textos/<hash>/`, propietario en archivos genéricos, auto-refresh de informes (consola + escritorio), etiquetas `[INFO]/[WARN]/[ERROR]` sin ANSI, auditoría de saturación (`LIMITE_ALCANZADO`), eliminación de `REGISTRO` por red y cero-residuos de tests (`@TempDir` + `logback-test.xml`) | Brechas cerradas con suites | ✅ código + tests |
| **Extra** | (Opcional) Ejecución multiproceso de tuberías por stdin/stdout (§3.6) | Demo con `FiltroMain` enlazados | ⏳ |

> **Criterios de aceptación de la Fase 10** = pruebas P1–P6 de §1 (§13.17–21).
> El orden ejecutado fue **12 → 14 → 15 (Java) → 16 (multiinstancia/paridad)**. Las pruebas manuales
> revelaron defectos que mantienen abierta la aceptación de paridad; la Fase 17 corrigió las
> implementaciones y consolidó las guías. Pendiente: **smoke integrado en seis direcciones y dos
> ventanas JavaFX → backlog 11**.

---

## 13. Criterios de verificación

1. `mvn test`: tuberías (hash SHA-256 correcto, 5 imágenes derivadas en orden,
   conteos exactos) con cobertura > 80 % en el módulo `utilerias`. Las pruebas de imagen
   verifican la cadena secuencial (cada derivada parte de la anterior) y que un fallo la detiene
   sin resultado parcial.
2. `docker-compose up mysql` + arranque servidor → consola muestra cada mensaje.
3. **3 clientes simultáneos**: 2 con el mismo código (multi-conexión) + 1 con otro;
   broadcast llega a todos; corte de red libera la sesión (`CLOSE_NOTICE`).
4. Subir imagen → verificar en `uploads/`: original + 5 derivados, hash SHA-256
   coincidente, y su registro en `mensajes` (DB).
5. Reiniciar servidor → historial de chat recuperado desde MySQL; clientes
   reconstruyen su historial local sin red (H2/SQLite) con las queries de
   `HISTORIAL_LOCAL.md`.
6. Informes: registrados / conectados×veces / mensajes con detalle / logs coinciden
   con la DB.
7. Límite de conexiones desde `server.properties` → con `max_conexiones=5`, la 6ª sesión (aunque sea de un código ya conectado) es rechazada.
8. **Prueba multilenguaje (C5)**: **conversación a 3 bandas** — clientes Java
   (principal), Python (espejo) y C# (espejo) loguean, se listan mutuamente,
   intercambian texto e imagen (preview con Qt/Pillow/BitmapImage) y reciben broadcast —
   todo sobre las tramas JSON de §4.0. El servidor no distingue lenguajes.
9. **Historial local multilenguaje (§5.2)**: los 3 clientes ejecutan el MISMO
   `schema-local.sql` y las MISMAS queries de `HISTORIAL_LOCAL.md` sobre su motor
   nativo (H2/SQLite/SQLite) y devuelven resultados idénticos para las mismas filas
   (verificación con fixture compartida en `common-protocol/`).
10. **Capas verificadas (C4)**: revisión de la regla de dependencias §11 en
    `server`, `client`, `client-python` y `client-dotnet`; en el servidor se comprueba
    con ArchUnit o Maven Enforcer.
11. **Adapter multi-motor (§5.4)**: mismo `.jar` del servidor contra **MySQL y
    PostgreSQL** (solo se cambia `db.active` + credenciales): login + texto + imagen
    + los 4 informes devuelven **lo mismo**; tercer pase opcional con Oracle.
    Verificar además que el esquema lo creó Flyway (no Hibernate) en cada motor.
12. **Componentes independientes e inversión de dependencias (§2.4, §2.5)**: cada módulo `comp-*` compila y
    pasa sus pruebas solo con su `int-*` y `dto` (y, solo `comp-protocolo-comunicacion`, el `codec`), usando
    dobles de sus callbacks; `mvn dependency:tree` de `servidor-app` muestra los `comp-*` únicamente en scope
    `runtime`; ningún módulo referencia a otro `comp-*`, a otro `int-*` ni a `servidor-app`.
13. **Filtrado secuencial y concurrencia entre mensajes (§3.3, §3.4)**: cada imagen genera 5
    archivos correctos (`01_` a `05_`) donde cada derivada parte de la anterior; al enviar varias
    imágenes a la vez, el log muestra hilos distintos (una cadena por imagen) sin mezclar resultados.
14. **Ejecución multiproceso (opcional, §3.6)**: dos `FiltroMain` enlazados por
    stdin/stdout producen el mismo resultado que la tubería en memoria.
15. **Usuarios y archivos**: `LISTAR_USUARIOS` devuelve todos los registrados con su estado; un
    `MENSAJE_ARCHIVO` (p. ej. un PDF) llega al destinatario con hash SHA-256 correcto y se
    descarga con `DESCARGAR_ARCHIVO`.
16. **Fidelidad al diagrama (§2.0)**: `DiagramaFidelidadTest` (ArchUnit) verifica que el conjunto de
    dependencias de uso entre paquetes y módulos coincide EXACTAMENTE con las flechas E1–E16 y los
    lollipops L1–L5 (con E15 resuelta por `PersistenciaPort`): una flecha extra o faltante rompe el
    build. Comprueba además que `ConexionesClientes` y los 5 `comp-*` no tienen dependencias salientes
    hacia otros nodos, y que solo `Fabrica` referencia clases de implementación.
17. **Auto-refresco de vistas del servidor (P1, Fase 10.4)**: en `VistaEscritorio`, las pestañas
    Estado/Usuarios/Conectados se actualizan solas cada ~5 s (`Timeline`) sin pulsar «Refrescar»;
    los eventos siguen llegando en vivo por `suscribirEventos` en ambas vistas. Abrir el escritorio,
    conectar un cliente y observar la fila aparecer sin tocar el botón.
18. **Registro solo por archivo plano (P2, Fase 10.2; eliminado en Fase 18)**: el cliente
     no ofrece alta de usuarios (sin `RegistroView` en Java/Python/C#); un `REGISTRO` enviado
     por red responde `ERROR "tipo no soportado: REGISTRO"`; añadir una fila al
     `usuarios_iniciales.csv` y reiniciar → el nuevo usuario puede hacer `LOGIN` (criterio 2).
19. **Inactividad 10 minutos (P3, Fase 10.1)**: con `red.sesion.timeout-segundos=600`, un cliente
    sin actividad es desconectado a los 10 min (±5 s), recibe `CLOSE_NOTICE` y el evento `TIMEOUT`
    aparece en la consola/auditoría; el propio cliente rechaza valores ≤ 0 (default 600).
20. **Botón único «Enviar» (P6, Fase 10.3)**: un solo botón envía (a) solo texto, (b) solo imagen
    (sin texto, válido), (c) texto + imagen adjunta; ambos campos y la vista previa quedan limpios
    tras enviar; no se puede enviar contenido vacío.
21. **Object Pool verificable (P4, §9)**: los 3 pools existen y son acotados por configuración
    (`red.pool.*`, `filtros.hilos`, `pool.maximumPoolSize`); con `max_conexiones=5` y pools al
    límite, las conexiones extras se rechazan sin degradar el proceso; no hay
    `ConexionPool` didáctico ni `newCachedThreadPool()` sin acotar en el código del servidor.
22. **Informes (Fase 9)**: los 4 informes de §8 se muestran en consola (`informe …`) y en la
    pestaña Informes del escritorio, y sus datos coinciden con MySQL (registrados, veces
   conectado, mensajes con hash/rutas, auditoría) para la misma fixture — criterio 6 extendido.
23. **Stacks UI (Fase 14)**: Java conserva FXML/controladores y pasa sus pruebas, incluida la ruta H2
    de 20 imágenes de 1 MiB bajo 3 s; Python renderiza login/chat con PySide6 + QFluentWidgets,
    entrega eventos del hilo TCP por señales y pasa tests de UI/capas; WPF compila las vistas con
    WPF UI y conserva sus tests. Ningún cliente cambia fachada, persistencia local o protocolo.
24. **Fase 15, directorio e identidad**: el listado completo se persiste desde `LISTAR_USUARIOS`; contacto,
    encabezado, remitente y detalle de lectura muestran `Nombre Apellido [CÓDIGO]`, con código fallback;
    búsqueda por nombre/código y presencia visible.
25. **Fase 15, mensajes**: imagen histórica aparece en la burbuja al quedar visible (también tras reinicio),
    descargas/decodificación ocurren fuera del hilo FX y se deduplican; texto e imagen admiten respuesta
    como cita ordinaria compatible con clientes sin cambios; texto, solo imagen y ambos se envían una vez.
26. **Fase 15, estado y rendimiento**: el ID de la fila local coincide con el envío TCP y su cola offline;
    acuses actualizan la fila correcta, permanecen en cola si hay desconexión y no generan contra-acuses.
    El refresco no lee blobs ni hace red periódica; vacío sin adjunto no habilita «Enviar».
27. **Fase 15, alcance**: no existe ruta Java a registro; JSON TCP y clientes Python/C# no cambian. Ejecutar
    `mvn -pl client/client-app -am test` y completar smoke JavaFX con servidor, reingreso, historial largo
    y varias imágenes antes de cerrar el último criterio manual.
28. **Fase 17, H2 por cuenta**: dos cuentas conservan perspectivas independientes del mismo ID;
    procesos del mismo código comparten su archivo con `AUTO_SERVER=TRUE`; la migración del legado
    conserva historial/pendientes y es idempotente. Completar comprobación manual JavaFX.
29. **Fase 16, sesiones**: con dos sockets del mismo usuario, `LOGOUT` de uno no desconecta el otro;
    presencia queda online mientras quede una sesión, y `KICK` cierra las restantes.
30. **Fase 16, paridad**: esquemas SQLite migran `estado`/`archivo_id`; identidades, paginación, mensajes
    texto/imagen, citas y acuses cubiertos por las suites de Python/.NET. Completar prueba manual de las
    seis direcciones Java↔Python↔.NET, incluyendo reinicio e imágenes.
31. **Fase 17, informes e imágenes**: informes 2–4 muestran `Nombre Apellido [CÓDIGO]`; solo LOGIN
     exitoso suma accesos; la presencia cambia al abrir/cerrar la primera/última sesión; cada paquete
     nuevo tiene el original y cinco derivadas en la carpeta del propietario y se descarga sin cambiar
     el wire protocol. Revisar consultas en la guía del servidor, sección DBeaver.
32. **Cero-residuos de tests (Fase 18)**: los E2E usan `@TempDir` para `uploads/work` y
     `logback-test.xml` solo-consola; tras `mvn -pl server/servidor-app,client/client-app -am test`
     no se recrean `server/servidor-app/{uploads,work,logs}` ni crece `server/logs`.
33. **Despliegue 4 PCs (Fase 18)**: seguir `GUIA_DESPLIEGUE_4PCS.md` con un PC por proceso;
     cada cliente apunta por IP LAN al `red.puerto` del servidor; respaldar MySQL + `uploads/` juntos.
