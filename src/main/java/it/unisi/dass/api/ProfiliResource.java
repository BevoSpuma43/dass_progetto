package it.unisi.dass.api;

import it.unisi.dass.analisi.ErroreAnalisi;
import it.unisi.dass.persistenza.ArchivioDocumenti;
import jakarta.ejb.EJB;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.function.Supplier;

/**
 * Profili e documenti cifrati del diario. L'elenco dei profili è pubblico; le richieste sui documenti
 * passano dal canale cifrato di {@link it.unisi.dass.sicurezza.CanaleCifratoFilter}, che le accetta solo
 * con uno scambio di chiavi del profilo indicato nel percorso.
 */
@Path("/profili")
@Produces(MediaType.APPLICATION_JSON)
public class ProfiliResource {
    @EJB
    private ArchivioDocumenti archivio;

    /** Profili disponibili con il numero di documenti cifrati: nessun contenuto del diario. */
    @GET
    public Response elenco() {
        return esegui(() -> archivio.profili(), Response.Status.OK);
    }

    /** Documenti cifrati di un tipo, dal più recente. */
    @GET
    @Path("{profilo}/documenti")
    public Response elenca(@PathParam("profilo") String profilo, @QueryParam("tipo") String tipo) {
        return esegui(() -> archivio.elenca(profilo, tipo), Response.Status.OK);
    }

    /** Un documento cifrato, esattamente come è conservato in H2. */
    @GET
    @Path("{profilo}/documenti/{id}")
    public Response leggi(@PathParam("profilo") String profilo, @PathParam("id") String id) {
        return esegui(() -> archivio.leggi(profilo, id), Response.Status.OK);
    }

    /** Crea (revisione attesa 0) o sostituisce un documento cifrato. */
    @PUT
    @Path("{profilo}/documenti/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response salva(@PathParam("profilo") String profilo, @PathParam("id") String id, JsonObject corpo) {
        return esegui(() -> archivio.salva(profilo, id, corpo), Response.Status.OK);
    }

    /** Elimina un documento; risponde sempre con l'esito, anche se era già assente. */
    @DELETE
    @Path("{profilo}/documenti/{id}")
    public Response elimina(@PathParam("profilo") String profilo, @PathParam("id") String id,
                            @QueryParam("revisione") String revisione) {
        return esegui(() -> {
            Long attesa = null;
            if (revisione != null) {
                try {
                    attesa = Long.valueOf(revisione);
                } catch (NumberFormatException e) {
                    throw new ErroreAnalisi(400, "REVISIONE_NON_VALIDA", "Revisione non valida.");
                }
            }
            return Json.createObjectBuilder().add("eliminato", archivio.elimina(profilo, id, attesa)).build();
        }, Response.Status.OK);
    }

    /** Esegue l'operazione e converte gli errori applicativi nella risposta JSON con codice e messaggio. */
    static Response esegui(Supplier<?> operazione, Response.Status stato) {
        try {
            return Response.status(stato).entity(operazione.get()).header("Cache-Control", "no-store").build();
        } catch (ErroreAnalisi e) {
            return errore(e);
        } catch (jakarta.ejb.EJBException e) {
            if (e.getCause() instanceof ErroreAnalisi errore) return errore(errore);
            throw e;
        }
    }

    static Response errore(ErroreAnalisi e) {
        JsonObject corpo = Json.createObjectBuilder().add("codice", e.codice()).add("messaggio", e.getMessage()).build();
        return Response.status(e.statoHttp()).type(MediaType.APPLICATION_JSON).header("Cache-Control", "no-store").entity(corpo).build();
    }
}
