package it.unisi.dass.api;

import it.unisi.dass.analisi.ErroreAnalisi;
import it.unisi.dass.persistenza.ArchivioDocumenti;
import it.unisi.dass.sicurezza.ScambioChiavi;
import jakarta.ejb.EJB;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Apre lo scambio di chiavi di un'azione del diario: riceve la chiave pubblica ECDH del browser e
 * restituisce quella di Payara con il sale HKDF. Nessuna chiave privata attraversa la rete.
 */
@Path("/scambi")
@Produces(MediaType.APPLICATION_JSON)
public class ScambiResource {
    private static final Pattern AZIONE = Pattern.compile("[a-z][a-z0-9-]{0,39}");

    @Inject
    ScambioChiavi scambi;

    @EJB
    private ArchivioDocumenti archivio;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response apri(JsonObject richiesta) {
        return ProfiliResource.esegui(() -> {
            if (richiesta == null) throw new ErroreAnalisi(400, "SCAMBIO_NON_VALIDO", "Invia profiloId, azione e chiavePubblica.");
            String profilo = richiesta.getString("profiloId", "");
            String azione = richiesta.getString("azione", "");
            archivio.verificaProfilo(profilo);
            if (!AZIONE.matcher(azione).matches())
                throw new ErroreAnalisi(400, "SCAMBIO_NON_VALIDO", "Nome dell'azione non valido.");
            byte[] chiave;
            try {
                chiave = Base64.getDecoder().decode(richiesta.getString("chiavePubblica", ""));
            } catch (IllegalArgumentException e) {
                throw new ErroreAnalisi(400, "CHIAVE_PUBBLICA_NON_VALIDA", "La chiave pubblica deve essere in Base64.");
            }
            return scambi.apri(profilo, azione, chiave).json();
        }, Response.Status.CREATED);
    }
}
