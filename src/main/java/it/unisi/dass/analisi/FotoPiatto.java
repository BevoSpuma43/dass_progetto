package it.unisi.dass.analisi;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;

/** Controlla e ricodifica le foto caricate e genera le miniature del diario. */
public final class FotoPiatto {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int LATO = 1280;

    /** Impedisce l'istanziazione di questa classe di utilità. */
    private FotoPiatto() {}

    /** Decodifica i pixel e crea un nuovo JPEG, escludendo nome file ed EXIF. */
    public static byte[] prepara(InputStream input) {
        return ridimensiona(input, LATO);
    }

    /**
     * Crea dal JPEG del pasto una miniatura con lato massimo di 120 pixel, mantenendo le proporzioni.
     */
    public static byte[] miniatura(byte[] jpeg) {
        return ridimensiona(new ByteArrayInputStream(jpeg), 120);
    }

    /**
     * Accetta JPEG e PNG leggibili, scarta risoluzioni eccessive e ricodifica i pixel in JPEG entro il
     * limite richiesto.
     */
    private static byte[] ridimensiona(InputStream input, int latoMassimo) {
        try (var stream = new MemoryCacheImageInputStream(input)) {
            var lettori = ImageIO.getImageReaders(stream);
            if (!lettori.hasNext()) throw formato();
            ImageReader reader = lettori.next();
            try {
                if (!Set.of("jpeg", "png").contains(reader.getFormatName().toLowerCase(Locale.ROOT))) throw formato();
                reader.setInput(stream, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if ((long) w * h > 40_000_000L || Math.max(w, h) > 20000) {
                    throw new ErroreAnalisi(413, "FOTO_GRANDE", "Riduci la risoluzione della foto.");
                }
                var param = reader.getDefaultReadParam();
                int passo = Math.max(1, Math.max(w, h) / latoMassimo);
                param.setSourceSubsampling(passo, passo, 0, 0);
                BufferedImage originale = reader.read(0, param);
                double scala = Math.min(1d, (double) latoMassimo / Math.max(w, h));
                var immagine = new BufferedImage(Math.max(1, (int) Math.round(w * scala)),
                        Math.max(1, (int) Math.round(h * scala)), BufferedImage.TYPE_INT_RGB);
                var grafica = immagine.createGraphics();
                try {
                    grafica.setColor(Color.WHITE);
                    grafica.fillRect(0, 0, immagine.getWidth(), immagine.getHeight());
                    grafica.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    grafica.drawImage(originale, 0, 0, immagine.getWidth(), immagine.getHeight(), null);
                } finally {
                    grafica.dispose();
                }
                var bytes = new ByteArrayOutputStream();
                ImageIO.write(immagine, "jpeg", bytes);
                return bytes.toByteArray();
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw formato();
        }
    }

    /** Costruisce l'errore HTTP 415 per un'immagine che non può essere letta come JPEG o PNG. */
    private static ErroreAnalisi formato() {
        return new ErroreAnalisi(415, "FOTO_ILLEGGIBILE", "Scegli una foto JPEG o PNG leggibile.");
    }
}
