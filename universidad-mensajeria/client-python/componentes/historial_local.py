class HistorialLocal:
    def __init__(self, db):
        self.db = db

    def guardar_mensaje(self, id_mensaje, origen, destino, tipo, contenido,
                        fecha_envio, hash_sha256="", num_caracteres=0,
                        num_palabras=0, nombre_archivo="", ruta_archivo="",
                        tamano_archivo=0, enviado=1, descargado=0,
                        archivo_id=None, estado="ENVIADO"):
        with self.db.conexion() as conn:
            conn.execute("""
                INSERT INTO historial_local 
                (id_mensaje, origen, destino, tipo, contenido, hash_sha256,
                 num_caracteres, num_palabras, nombre_archivo, archivo_id,
                 ruta_archivo, tamano_archivo, fecha_envio, enviado, descargado, estado)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id_mensaje) DO UPDATE SET
                    contenido = excluded.contenido,
                    hash_sha256 = COALESCE(excluded.hash_sha256, historial_local.hash_sha256),
                    num_caracteres = COALESCE(excluded.num_caracteres, historial_local.num_caracteres),
                    num_palabras = COALESCE(excluded.num_palabras, historial_local.num_palabras),
                    nombre_archivo = COALESCE(excluded.nombre_archivo, historial_local.nombre_archivo),
                    archivo_id = COALESCE(excluded.archivo_id, historial_local.archivo_id),
                    ruta_archivo = COALESCE(excluded.ruta_archivo, historial_local.ruta_archivo),
                    tamano_archivo = COALESCE(excluded.tamano_archivo, historial_local.tamano_archivo),
                    descargado = MAX(excluded.descargado, historial_local.descargado),
                    estado = CASE
                        WHEN historial_local.estado = 'LEIDO' OR excluded.estado = 'LEIDO' THEN 'LEIDO'
                        WHEN historial_local.estado = 'ENTREGADO' AND excluded.estado IN ('PENDIENTE', 'ENVIADO') THEN 'ENTREGADO'
                        WHEN historial_local.estado = 'ENVIADO' AND excluded.estado = 'PENDIENTE' THEN 'ENVIADO'
                        ELSE excluded.estado END
            """, (id_mensaje, origen, destino, tipo, contenido, hash_sha256,
                  num_caracteres, num_palabras, nombre_archivo, archivo_id,
                  ruta_archivo, tamano_archivo, fecha_envio, enviado, descargado, estado))
            
    def obtener_conversacion(self, user1, user2, limit=50, offset=0):
        with self.db.conexion() as conn:
            cursor = conn.execute("""
                SELECT id_mensaje, origen, destino, tipo,
                       CASE WHEN tipo = 'MENSAJE_IMAGEN' THEN NULL ELSE contenido END AS contenido,
                       hash_sha256, num_caracteres, num_palabras, nombre_archivo,
                       archivo_id, ruta_archivo, tamano_archivo, fecha_envio,
                       enviado, descargado, estado
                FROM historial_local
                WHERE (origen = ? AND destino = ?) OR (origen = ? AND destino = ?)
                ORDER BY fecha_envio DESC
                LIMIT ? OFFSET ?
            """, (user1, user2, user2, user1, limit, offset))
            return [dict(row) for row in cursor.fetchall()]

    def obtener_por_id(self, id_mensaje):
        with self.db.conexion() as conn:
            fila = conn.execute(
                "SELECT * FROM historial_local WHERE id_mensaje = ?", (id_mensaje,)
            ).fetchone()
            return dict(fila) if fila else None

    def ids_no_leidos(self, yo, otro):
        with self.db.conexion() as conn:
            filas = conn.execute("""
                SELECT id_mensaje FROM historial_local
                WHERE origen = ? AND destino = ? AND estado <> 'LEIDO'
                  AND tipo IN ('MENSAJE_TEXTO', 'MENSAJE_IMAGEN', 'MENSAJE_ARCHIVO',
                               'BROADCAST', 'SYNC_LOGIN')
                ORDER BY fecha_envio ASC
            """, (otro, yo)).fetchall()
            return [fila[0] for fila in filas]

    def contar_no_leidos_por_contacto(self, yo):
        """Badge mockup §3: no leídos agrupados por remitente en una sola query."""
        with self.db.conexion() as conn:
            filas = conn.execute("""
                SELECT origen AS codigo, COUNT(*) AS n
                FROM historial_local
                WHERE destino = ? AND estado <> 'LEIDO'
                  AND tipo IN ('MENSAJE_TEXTO', 'MENSAJE_IMAGEN', 'MENSAJE_ARCHIVO',
                               'BROADCAST', 'SYNC_LOGIN')
                GROUP BY origen
            """, (yo,)).fetchall()
            return {fila[0]: fila[1] for fila in filas}

    def marcar_estado_de_conversacion(self, yo, otro, estado):
        with self.db.conexion() as conn:
            conn.execute("""
                UPDATE historial_local SET estado = ?
                WHERE origen = ? AND destino = ? AND tipo IN
                  ('MENSAJE_TEXTO', 'MENSAJE_IMAGEN', 'MENSAJE_ARCHIVO',
                   'BROADCAST', 'SYNC_LOGIN')
            """, (estado, otro, yo))

    def contar_conversacion(self, user1, user2):
        """HISTORIAL_LOCAL.md §2: conteo para totalPaginas."""
        with self.db.conexion() as conn:
            cursor = conn.cursor()
            cursor.execute("""
                SELECT COUNT(*)
                FROM historial_local
                WHERE (origen = ? AND destino = ?)
                   OR (origen = ? AND destino = ?)
            """, (user1, user2, user2, user1))
            return cursor.fetchone()[0]

    def marcar_descargado(self, id_mensaje, ruta_archivo):
        """HISTORIAL_LOCAL.md §3: marca archivo descargado y guarda su ruta."""
        with self.db.conexion() as conn:
            cursor = conn.cursor()
            cursor.execute("""
                UPDATE historial_local
                SET descargado = 1, ruta_archivo = ?
                WHERE id_mensaje = ?
            """, (ruta_archivo, id_mensaje))
            conn.commit()

    def marcar_estado(self, id_mensaje, estado):
        with self.db.conexion() as conn:
            conn.execute("""
                UPDATE historial_local SET estado = CASE
                    WHEN estado = 'LEIDO' OR ? = 'LEIDO' THEN 'LEIDO'
                    WHEN estado = 'ENTREGADO' AND ? IN ('PENDIENTE', 'ENVIADO') THEN estado
                    WHEN estado = 'ENVIADO' AND ? = 'PENDIENTE' THEN estado
                    ELSE ? END
                WHERE id_mensaje = ?
            """, (estado, estado, estado, estado, id_mensaje))

    def actualizar_archivo_id(self, id_mensaje, archivo_id):
        with self.db.conexion() as conn:
            conn.execute("UPDATE historial_local SET archivo_id = ? WHERE id_mensaje = ?",
                         (archivo_id, id_mensaje))

    def actualizar_hash(self, id_mensaje, hash_sha256):
        with self.db.conexion() as conn:
            conn.execute("UPDATE historial_local SET hash_sha256 = ? WHERE id_mensaje = ?",
                         (hash_sha256, id_mensaje))

    def guardar_pendiente(self, id_pend, tipo, origen, destino, contenido, nombre_archivo, payload, fecha_creado):
        with self.db.conexion() as conn:
            cursor = conn.cursor()
            # Upsert portable del contrato: pre-DELETE para re-envios del mismo id.
            cursor.execute("DELETE FROM pendientes_envio WHERE id = ?", (id_pend,))
            cursor.execute("""
                INSERT INTO pendientes_envio (id, tipo, origen, destino, contenido, nombre_archivo, payload, fecha_creado, intentos)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)
            """, (id_pend, tipo, origen, destino, contenido, nombre_archivo, payload, fecha_creado))
            conn.commit()

    def obtener_pendientes(self):
        with self.db.conexion() as conn:
            cursor = conn.cursor()
            cursor.execute("SELECT * FROM pendientes_envio ORDER BY fecha_creado ASC")
            return [dict(row) for row in cursor.fetchall()]

    def eliminar_pendiente(self, id_pend):
        with self.db.conexion() as conn:
            cursor = conn.cursor()
            cursor.execute("DELETE FROM pendientes_envio WHERE id = ?", (id_pend,))
            conn.commit()

    def incrementar_intento(self, id_pend, ultimo_error):
        """HISTORIAL_LOCAL.md §5: cuenta reintentos del pendiente."""
        with self.db.conexion() as conn:
            cursor = conn.cursor()
            cursor.execute("""
                UPDATE pendientes_envio
                SET intentos = intentos + 1, ultimo_error = ?
                WHERE id = ?
            """, (ultimo_error, id_pend))
            conn.commit()
            
    def actualizar_cache_usuario(self, codigo, nombres, apellidos, programa, conectado, fecha_registro, actualizado):
        with self.db.conexion() as conn:
            cursor = conn.cursor()
            cursor.execute("DELETE FROM cache_usuarios WHERE codigo = ?", (codigo,))
            cursor.execute("""
                INSERT INTO cache_usuarios (codigo, nombres, apellidos, programa, conectado, fecha_registro, actualizado)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """, (codigo, nombres, apellidos, programa, conectado, fecha_registro, actualizado))
            conn.commit()

    def listar_cache_usuarios(self):
        """HISTORIAL_LOCAL.md §7: directorio local, conectados primero."""
        with self.db.conexion() as conn:
            cursor = conn.cursor()
            cursor.execute("""
                SELECT codigo, nombres, apellidos, programa, conectado, fecha_registro, actualizado
                FROM cache_usuarios
                ORDER BY conectado DESC, apellidos ASC
            """)
            return [dict(row) for row in cursor.fetchall()]

    def marcar_conectados(self, codigos):
        """Refresca banderas de conexion con el listado del servidor."""
        with self.db.conexion() as conn:
            cursor = conn.cursor()
            cursor.execute("UPDATE cache_usuarios SET conectado = 0")
            for codigo in codigos:
                cursor.execute("UPDATE cache_usuarios SET conectado = 1 WHERE codigo = ?", (codigo,))
            conn.commit()
