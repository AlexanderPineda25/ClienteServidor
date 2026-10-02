package universidad.mensajeria.utilerias.imagen;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Operaciones puras de imagen (JDK, sin dependencias). Cada transformacion
 * devuelve una NUEVA imagen; la escritura va al directorio de trabajo.
 */
public final class Imagenes {

    private Imagenes() {
    }

    /**
     * FASE 11.2: valida magic bytes antes de la tuberia (PNG, JPEG, GIF, BMP).
     * Rechaza texto/binarios disfrazados sin llegar a decodificar la imagen.
     */
    public static boolean esImagenValida(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return false;
        }
        int b0 = bytes[0] & 0xFF, b1 = bytes[1] & 0xFF,
                b2 = bytes[2] & 0xFF, b3 = bytes[3] & 0xFF;
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) {
            return true; // PNG
        }
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) {
            return true; // JPEG
        }
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38) {
            return true; // GIF87a/GIF89a
        }
        return b0 == 0x42 && b1 == 0x4D; // BMP
    }

    public static BufferedImage leer(Path archivo) throws IOException {
        byte[] bytes = Files.readAllBytes(archivo);
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            BufferedImage imagen = ImageIO.read(in);
            if (imagen == null) {
                throw new IOException("no es una imagen legible: " + archivo);
            }
            return imagen;
        }
    }

    public static Path escribir(BufferedImage imagen, Path directorio, String nombre) throws IOException {
        Files.createDirectories(directorio);
        Path destino = directorio.resolve(nombre);
        try (OutputStream out = Files.newOutputStream(destino)) {
            ImageIO.write(imagen, "png", out);
        }
        return destino;
    }

    public static BufferedImage nuevaCopia(BufferedImage origen) {
        BufferedImage copia = new BufferedImage(
                origen.getWidth(), origen.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copia.createGraphics();
        try {
            g.drawImage(origen, 0, 0, null);
        } finally {
            g.dispose();
        }
        return copia;
    }

    public static BufferedImage aGrises(BufferedImage origen) {
        BufferedImage salida = nuevaCopia(origen);
        for (int y = 0; y < salida.getHeight(); y++) {
            for (int x = 0; x < salida.getWidth(); x++) {
                int rgb = salida.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int gris = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                salida.setRGB(x, y, (gris << 16) | (gris << 8) | gris);
            }
        }
        return salida;
    }

    public static BufferedImage aSepia(BufferedImage origen) {
        BufferedImage salida = nuevaCopia(origen);
        for (int y = 0; y < salida.getHeight(); y++) {
            for (int x = 0; x < salida.getWidth(); x++) {
                int rgb = salida.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int tr = Math.min(255, (int) (0.393 * r + 0.769 * g + 0.189 * b));
                int tg = Math.min(255, (int) (0.349 * r + 0.686 * g + 0.168 * b));
                int tb = Math.min(255, (int) (0.272 * r + 0.534 * g + 0.131 * b));
                salida.setRGB(x, y, (tr << 16) | (tg << 8) | tb);
            }
        }
        return salida;
    }

    public static BufferedImage girar(BufferedImage origen, int grados) {
        int pasos = ((grados % 360) + 360) % 360 / 90;
        BufferedImage actual = origen;
        for (int i = 0; i < pasos; i++) {
            actual = girar90(actual);
        }
        return actual == origen ? nuevaCopia(origen) : actual;
    }

    private static BufferedImage girar90(BufferedImage origen) {
        int w = origen.getWidth();
        int h = origen.getHeight();
        BufferedImage salida = new BufferedImage(h, w, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                salida.setRGB(h - 1 - y, x, origen.getRGB(x, y) & 0xFFFFFF);
            }
        }
        return salida;
    }

    public static BufferedImage ajustarBrillo(BufferedImage origen, double factor) {
        BufferedImage salida = nuevaCopia(origen);
        for (int y = 0; y < salida.getHeight(); y++) {
            for (int x = 0; x < salida.getWidth(); x++) {
                int rgb = salida.getRGB(x, y);
                int r = Math.min(255, Math.max(0, (int) (((rgb >> 16) & 0xFF) * factor)));
                int g = Math.min(255, Math.max(0, (int) (((rgb >> 8) & 0xFF) * factor)));
                int b = Math.min(255, Math.max(0, (int) ((rgb & 0xFF) * factor)));
                salida.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return salida;
    }

    public static BufferedImage reducir(BufferedImage origen, double factor) {
        int w = Math.max(1, (int) (origen.getWidth() * factor));
        int h = Math.max(1, (int) (origen.getHeight() * factor));
        BufferedImage salida = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sx = Math.min(origen.getWidth() - 1, (int) (x / factor));
                int sy = Math.min(origen.getHeight() - 1, (int) (y / factor));
                salida.setRGB(x, y, origen.getRGB(sx, sy) & 0xFFFFFF);
            }
        }
        return salida;
    }
}
