package it.unisi.dass.api;

import it.unisi.dass.analisi.ClaudeClient;
import it.unisi.dass.analisi.ErroreAnalisi;
import it.unisi.dass.analisi.FotoPiatto;
import it.unisi.dass.persistenza.ArchivioCatalogo;
import jakarta.ejb.EJB;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.util.Base64;

/**
 * Analisi del piatto con Claude. È l'unico punto in cui Payara riceve foto e descrizione in chiaro:
 * l'utente lo conferma esplicitamente nel modulo. La risorsa non salva nulla: la bozza, la foto e la
 * proposta vengono salvate dal browser solo dopo averle cifrate con la chiave del profilo.
 */
@Path("/analisi-piatto")
@Produces(MediaType.APPLICATION_JSON)
public class AnalisiResource {
    /** Catalogo pubblico: Claude sceglie per ogni ingrediente una di queste schede. */
    @EJB
    private ArchivioCatalogo catalogo;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response analizza(JsonObject richiesta) {
        return ProfiliResource.esegui(() -> {
            if (richiesta == null || !richiesta.getBoolean("consenso", false))
                throw new ErroreAnalisi(400, "CONSENSO_MANCANTE", "Conferma l’invio di foto e descrizione a Payara e ad Anthropic.");
            byte[] foto;
            try {
                foto = Base64.getDecoder().decode(richiesta.getString("foto", ""));
            } catch (IllegalArgumentException e) {
                throw new ErroreAnalisi(400, "FOTO_ILLEGGIBILE", "La foto deve essere in Base64.");
            }
            if (foto.length == 0) throw new ErroreAnalisi(400, "FOTO_ASSENTE", "Seleziona la foto di un piatto.");
            if (foto.length > FotoPiatto.MAX_BYTES) throw new ErroreAnalisi(413, "UPLOAD_GRANDE", "La foto deve pesare al massimo 5 MiB.");
            String caption = richiesta.getString("caption", "").strip();
            if (caption.length() > 500) throw new ErroreAnalisi(400, "CAPTION_LUNGA", "Usa al massimo 500 caratteri per la descrizione.");
            byte[] jpeg = FotoPiatto.prepara(new ByteArrayInputStream(foto));
            return Json.createObjectBuilder().add("proposta", new ClaudeClient().analizza(jpeg, caption, catalogo.catalogo(""))).build();
        }, Response.Status.OK);
    }
}
