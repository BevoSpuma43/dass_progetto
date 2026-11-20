package it.unisi.dass.api;

import it.unisi.dass.analisi.ErroreAnalisi;
import it.unisi.dass.persistenza.ArchivioBozze;
import it.unisi.dass.servizi.AnteprimaPasto;
import it.unisi.dass.servizi.RichiestaPasto;
import jakarta.ejb.EJB;
import jakarta.json.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.Set;

/** Gestisce i contratti HTTP per lettura, foto, correzione, anteprima e rimozione delle bozze. */
@Path("/bozze")
@Produces(MediaType.APPLICATION_JSON)
public class BozzeResource {
    @EJB private ArchivioBozze bozze;

    /** Restituisce la bozza identificata dall'URL, oppure HTTP 404 se è scaduta o mancante. */
    @GET @Path("{id}")
    public Response leggi(@PathParam("id") String id) {
        return risposta(bozze.leggi(id), MediaType.APPLICATION_JSON);
    }

    /** Restituisce la foto JPEG della bozza finché questa è disponibile. */
    @GET @Path("{id}/foto") @Produces({"image/jpeg",MediaType.APPLICATION_JSON})
    public Response foto(@PathParam("id") String id) {
        return risposta(bozze.foto(id), "image/jpeg");
    }

    /**
     * Elimina la bozza e risponde con HTTP 204 anche se è già assente. Se indicata, la revisione
     * protegge dalle modifiche concorrenti.
     */
    @DELETE @Path("{id}")
    public Response rimuovi(@PathParam("id") String id, @QueryParam("revisione") String revisione) {
        try {
            Long attesa = revisione == null ? null : Long.valueOf(revisione);
            if (attesa != null && attesa < 0) throw new NumberFormatException();
            bozze.rimuovi(id, attesa);
            return Response.noContent().header("Cache-Control", "no-store").build();
        } catch (NumberFormatException e) {
            return errore(400, "REVISIONE_NON_VALIDA", "Revisione non valida.");
        } catch (ErroreAnalisi e) {
            return errore(e.statoHttp(), e.codice(), e.getMessage());
        }
    }

    /** Riceve revisione e ingredienti dal client e inoltra il salvataggio all'archivio delle bozze. */
    @PUT @Path("{id}/ingredienti") @Consumes(MediaType.APPLICATION_JSON)
    public Response correggi(@PathParam("id") String id, JsonObject richiesta) {
        return elaboraRighe(id, richiesta, false);
    }

    /** Controlla campi e scenario della richiesta, quindi chiede il calcolo in sola lettura. */
    @POST @Path("{id}/anteprima") @Consumes(MediaType.APPLICATION_JSON)
    public Response anteprima(@PathParam("id") String id, JsonObject richiesta) {
        if (richiesta != null && !Set.of("revisione","ingredienti","scenarioId","piattoId","consumatoAlle")
                .containsAll(richiesta.keySet()))
            return errore(400,"CAMPI_NON_VALIDI","Invia solo revisione, ingredienti, scenarioId, piattoId e consumatoAlle.");
        if (richiesta != null && richiesta.containsKey("scenarioId") &&
                !Json.createValue(AnteprimaPasto.REGOLA).equals(richiesta.get("scenarioId")))
            return errore(400,"SCENARIO_NON_VALIDO","Scegli lo scenario dimostrativo.");
        return elaboraRighe(id, richiesta, true);
    }

    /**
     * Valida il corpo comune di correzione e anteprima e inoltra all'archivio l'operazione richiesta.
     */
    private Response elaboraRighe(String id, JsonObject richiesta, boolean anteprima) {
        try {
            if (richiesta == null || !(richiesta.get("revisione") instanceof JsonNumber n) || !n.isIntegral()
                    || !(richiesta.get("ingredienti") instanceof JsonArray righe)) {
                return errore(400,"BOZZA_NON_VALIDA","Invia revisione e ingredienti.");
            }
            long revisione = n.longValueExact();
            if (!anteprima) return risposta(bozze.correggi(id,revisione,righe),MediaType.APPLICATION_JSON);
            var riferimento = richiesta.getOrDefault("piattoId",JsonValue.NULL);
            if (riferimento != JsonValue.NULL && (!(riferimento instanceof JsonString p)
                    || !p.getString().matches("[A-Za-z0-9_-]{1,128}"))) {
                return errore(400,"PIATTO_NON_VALIDO","Scegli un piatto abituale.");
            }
            var orario = richiesta.containsKey("consumatoAlle") ? RichiestaPasto.orario(richiesta) : null;
            var dati = bozze.anteprima(id,revisione,righe,riferimento instanceof JsonString p ? p.getString() : null);
            if (dati != null && orario != null)
                dati = Json.createObjectBuilder(dati).add("consumatoAlle",orario.toString()).build();
            return risposta(dati,MediaType.APPLICATION_JSON);
        } catch (ErroreAnalisi e) {
            return errore(e.statoHttp(),e.codice(),e.getMessage());
        } catch (ArithmeticException e) {
            return errore(400,"REVISIONE_NON_VALIDA","Revisione non valida.");
        }
    }

    /** Produce una risposta senza cache e usa HTTP 404 se il servizio non ha trovato la bozza. */
    private static Response risposta(Object contenuto, String tipo) {
        if (contenuto == null) return errore(404,"BOZZA_ASSENTE","Bozza scaduta o inesistente.");
        return Response.ok(contenuto,tipo).header("Cache-Control","no-store").build();
    }

    /** Costruisce la risposta JSON di errore con stato HTTP, codice e messaggio. */
    private static Response errore(int http, String codice, String messaggio) {
        return Response.status(http).type(MediaType.APPLICATION_JSON).header("Cache-Control","no-store")
                .entity(Json.createObjectBuilder().add("codice",codice).add("messaggio",messaggio).build()).build();
    }
}
