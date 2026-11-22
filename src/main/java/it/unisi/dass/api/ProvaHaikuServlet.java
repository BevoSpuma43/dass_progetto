package it.unisi.dass.api;

import it.unisi.dass.analisi.*;
import it.unisi.dass.persistenza.ArchivioBozze;
import jakarta.ejb.EJB;
import jakarta.json.*;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Riceve le foto multipart per la prova con Claude e per l'analisi che crea una bozza. */
@WebServlet({"/prove/haiku", "/api/analisi-piatto"})
@MultipartConfig(maxFileSize = FotoPiatto.MAX_BYTES, maxRequestSize = FotoPiatto.MAX_BYTES + 65536L)
public class ProvaHaikuServlet extends HttpServlet {
    @EJB
    private ArchivioBozze bozze;

    /**
     * Delega la foto normalizzata e la descrizione al client Claude. Il collaudo sovrascrive questo
     * punto per simulare il fornitore.
     */
    protected JsonObject analizzaConClaude(byte[] foto, String caption) {
        return new ClaudeClient().analizza(foto, caption);
    }

    /**
     * Valida l'upload, prepara il JPEG e invoca l'analisi. Per la route persistente salva la bozza
     * prima della chiamata esterna e la restituisce anche in caso di errore applicativo.
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        res.setCharacterEncoding("UTF-8");
        res.setContentType("application/json");
        res.setHeader("Cache-Control", "no-store");
        JsonObject risposta;
        String bozzaId = null;
        boolean prova = "/prove/haiku".equals(req.getServletPath());
        try {
            // Il form locale imposta questo header. Un form HTML di un altro sito non può inviarlo.
            if (!"1".equals(req.getHeader(prova ? "X-DASS-Prova" : "X-DASS-Analisi")))
                throw new ErroreAnalisi(403, "ORIGINE", "Apri la prova dalla pagina DASS.");
            String mime = req.getContentType();
            if (mime == null || !mime.toLowerCase(Locale.ROOT).startsWith("multipart/form-data"))
                throw new ErroreAnalisi(415, "FORMATO", "Invia foto e descrizione come multipart/form-data.");
            Part foto = req.getPart("foto");
            if (foto == null || foto.getSize() == 0)
                throw new ErroreAnalisi(400, "FOTO_ASSENTE", "Seleziona la foto di un piatto.");
            Part descrizione = req.getPart("caption");
            String caption = "";
            if (descrizione != null) {
                if (descrizione.getSize() > 2000) throw captionLunga();
                try (var input = descrizione.getInputStream()) {
                    caption = new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
                }
                if (caption.length() > 500) throw captionLunga();
            }
            byte[] jpeg;
            try (var input = foto.getInputStream()) { jpeg = FotoPiatto.prepara(input); }
            // La prima transazione termina prima di contattare il servizio esterno.
            if (!prova) bozzaId = bozze.crea(jpeg, caption);
            var proposta = analizzaConClaude(jpeg, caption);
            risposta = prova ? proposta : bozze.completa(bozzaId, proposta);
        } catch (ErroreAnalisi e) {
            res.setStatus(e.statoHttp());
            risposta = errore(e.codice(), e.getMessage());
            if (bozzaId != null) {
                risposta = Json.createObjectBuilder(bozze.leggi(bozzaId))
                        .add("codice",e.codice()).add("messaggio",e.getMessage()).build();
            }
        } catch (IllegalStateException e) {
            res.setStatus(413);
            risposta = errore("UPLOAD_GRANDE", "La foto deve pesare al massimo 5 MiB.");
        } catch (ServletException e) {
            res.setStatus(400);
            risposta = errore("MULTIPART_NON_VALIDO", "Caricamento illeggibile. Seleziona di nuovo la foto.");
        }
        res.getWriter().write(risposta.toString());
    }

    /** Prepara il corpo JSON con codice e messaggio dell'errore. */
    private static JsonObject errore(String codice, String messaggio) {
        return Json.createObjectBuilder().add("codice", codice).add("messaggio", messaggio).build();
    }

    /** Costruisce l'errore HTTP 400 per una descrizione che supera i limiti consentiti. */
    private static ErroreAnalisi captionLunga() {
        return new ErroreAnalisi(400, "CAPTION_LUNGA", "Usa al massimo 500 caratteri per la descrizione.");
    }
}
