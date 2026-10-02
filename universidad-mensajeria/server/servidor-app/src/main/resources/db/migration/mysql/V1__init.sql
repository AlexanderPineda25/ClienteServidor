-- V1__init.sql — MySQL 8 (esquema requerimientos.txt §5.1)

CREATE TABLE usuarios (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    codigo VARCHAR(20) NOT NULL UNIQUE,
    nombres VARCHAR(100) NOT NULL,
    apellidos VARCHAR(100) NOT NULL,
    password_hash VARCHAR(120) NOT NULL,
    programa_academico VARCHAR(120) NOT NULL,
    estado VARCHAR(20) NOT NULL DEFAULT 'PENDIENTE',
    conectado BOOLEAN NOT NULL DEFAULT FALSE,
    fecha_ultima_conexion DATETIME NULL,
    fecha_registro DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    INDEX idx_usuario_estado (estado),
    INDEX idx_usuario_conectado (conectado)
) ENGINE=InnoDB;

CREATE TABLE sesiones_activas (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    usuario_id BIGINT NOT NULL,
    token VARCHAR(255) NOT NULL UNIQUE,
    ip VARCHAR(45) NOT NULL,
    inicio DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ultima_actividad DATETIME,
    activa BOOLEAN NOT NULL DEFAULT TRUE,
    aplicacion VARCHAR(50),
    version_app VARCHAR(30),
    FOREIGN KEY (usuario_id) REFERENCES usuarios(id) ON DELETE CASCADE,
    INDEX idx_sesion_usuario (usuario_id),
    INDEX idx_sesion_activa (activa)
) ENGINE=InnoDB;

CREATE TABLE conexiones (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    usuario_id BIGINT NOT NULL,
    direccion_ip VARCHAR(45) NOT NULL,
    hora_conexion DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    hora_desconexion DATETIME,
    mensajes_sesion INT NOT NULL DEFAULT 0,
    activa BOOLEAN NOT NULL DEFAULT TRUE,
    sistema_operativo VARCHAR(120),
    dispositivo VARCHAR(120),
    sesion_id BIGINT,
    FOREIGN KEY (usuario_id) REFERENCES usuarios(id) ON DELETE CASCADE,
    FOREIGN KEY (sesion_id) REFERENCES sesiones_activas(id) ON DELETE SET NULL,
    INDEX idx_conexion_usuario (usuario_id),
    INDEX idx_conexion_activa (activa)
) ENGINE=InnoDB;

CREATE TABLE archivos (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    nombre VARCHAR(255) NOT NULL,
    ruta VARCHAR(767) NOT NULL UNIQUE,
    tamano BIGINT NOT NULL DEFAULT 0,
    tipo VARCHAR(30) NOT NULL,
    mime VARCHAR(255),
    hash_sha256 CHAR(64) NOT NULL,
    propietario_id BIGINT NOT NULL,
    fecha_subida DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (propietario_id) REFERENCES usuarios(id) ON DELETE CASCADE,
    INDEX idx_archivo_propietario (propietario_id),
    INDEX idx_archivo_hash (hash_sha256)
) ENGINE=InnoDB;

CREATE TABLE mensajes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    remitente_id BIGINT NOT NULL,
    destinatario_id BIGINT NOT NULL,
    tipo VARCHAR(20) NOT NULL,
    contenido TEXT,
    hash_sha256 CHAR(64),
    num_caracteres INT,
    num_palabras INT,
    etapas_filtrado TEXT,
    fecha_envio DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ip_remitente VARCHAR(45),
    ip_destinatario VARCHAR(45),
    archivo_id BIGINT,
    FOREIGN KEY (remitente_id) REFERENCES usuarios(id) ON DELETE CASCADE,
    FOREIGN KEY (destinatario_id) REFERENCES usuarios(id) ON DELETE CASCADE,
    FOREIGN KEY (archivo_id) REFERENCES archivos(id) ON DELETE SET NULL,
    INDEX idx_mensaje_conv (remitente_id, destinatario_id, fecha_envio),
    INDEX idx_mensaje_dest (destinatario_id)
) ENGINE=InnoDB;

CREATE TABLE registro_acciones (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tipo VARCHAR(30) NOT NULL,
    usuario_id BIGINT,
    descripcion TEXT NOT NULL,
    fecha DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ip VARCHAR(45),
    detalles TEXT,
    FOREIGN KEY (usuario_id) REFERENCES usuarios(id) ON DELETE SET NULL,
    INDEX idx_accion_tipo (tipo),
    INDEX idx_accion_fecha (fecha)
) ENGINE=InnoDB;

CREATE TABLE configuracion_limites (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    max_conexiones_usuario INT NOT NULL DEFAULT 3,
    max_conexiones_totales INT NOT NULL DEFAULT 100,
    max_archivos_usuario INT NOT NULL DEFAULT 100,
    max_tamano_archivo BIGINT NOT NULL DEFAULT 10485760,
    max_archivos_dia INT NOT NULL DEFAULT 20,
    activa BOOLEAN NOT NULL DEFAULT TRUE,
    fecha_actualizacion DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_config_activa (activa)
) ENGINE=InnoDB;

INSERT INTO configuracion_limites (activa) VALUES (TRUE);
