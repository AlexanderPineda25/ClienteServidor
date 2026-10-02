-- V2: tope propio de 50 MB para archivos fragmentados (ARCHIVO_INICIO/PARTE/FIN).
ALTER TABLE configuracion_limites MODIFY (max_tamano_archivo DEFAULT 52428800);
UPDATE configuracion_limites SET max_tamano_archivo = 52428800 WHERE max_tamano_archivo = 10485760;
