package it.unisi.dass.api;

import it.unisi.dass.analisi.ErroreAnalisi;
import it.unisi.dass.persistenza.ArchivioPasti;
import it.unisi.dass.servizi.RichiestaPasto;
import jakarta.ejb.EJB;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.function.Supplier;

/** Espone la registrazione e le letture del diario alimentare. */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class PastiResource {
    @EJB private ArchivioPasti pasti;

    /**
     * Valida e registra il pasto, quindi risponde con HTTP 201 e con l'indirizzo del dettaglio
     * nell'header Location.
     */
    @POST @Path("pasti") @Consumes(MediaType.APPLICATION_JSON)
    public Response registra(JsonObject richiesta, @Context UriInfo uri) {
        try {
            var dati = pasti.registra(RichiestaPasto.leggi(richiesta));
            var posizione = uri.getAbsolutePathBuilder().path(dati.getString("pastoId")).build();
            return Response.created(posizione).header("Cache-Control","no-store").entity(dati).build();
        } catch (ErroreAnalisi e) {
            return errore(e);
        }
    }

    /** Restituisce ingredienti, valori nutrizionali e simulazione del pasto indicato dall'ID. */
    @GET @Path("pasti/{id}")
    public Response dettaglio(@PathParam("id") String id) {
        return leggi(() -> pasti.dettaglio(id), MediaType.APPLICATION_JSON);
    }

    /** Restituisce il JPEG originale o la miniatura del pasto, secondo il parametro miniatura. */
    @GET @Path("pasti/{id}/foto") @Produces({"image/jpeg", MediaType.APPLICATION_JSON})
    public Response foto(@PathParam("id") String id, @QueryParam("miniatura") boolean miniatura) {
        return leggi(() -> pasti.foto(id, miniatura), "image/jpeg");
    }

    /** Elenca i pasti registrati per uno specifico piatto, dal più recente. */
    @GET @Path("piatti/{id}/pasti")
    public Response pastiDelPiatto(@PathParam("id") String id) {
        return leggi(() -> pasti.pastiDelPiatto(id), MediaType.APPLICATION_JSON);
    }

    /** Elenca i piatti del profilo richiesto e indica l'ultimo pasto quando disponibile. */
    @GET @Path("pazienti/{id}/piatti")
    public Response piattiDelPaziente(@PathParam("id") String id) {
        return leggi(() -> pasti.piattiDelPaziente(id), MediaType.APPLICATION_JSON);
    }

    /** Restituisce lo storico del profilo con macro e risultato della simulazione salvato. */
    @GET @Path("pazienti/{id}/pasti")
    public Response pastiDelPaziente(@PathParam("id") String id) {
        return leggi(() -> pasti.pastiDelPaziente(id), MediaType.APPLICATION_JSON);
    }

    /** Esegue una lettura del diario e mappa il risultato o l'errore applicativo nella risposta HTTP. */
    private static Response leggi(Supplier<?> azione, String tipo) {
        try {
            return Response.ok(azione.get(),tipo).header("Cache-Control","no-store").build();
        } catch (ErroreAnalisi e) {
            return errore(e);
        }
    }

    /** Serializza un errore applicativo nel corpo JSON con lo stato HTTP corrispondente. */
    private static Response errore(ErroreAnalisi e) {
        var dati = Json.createObjectBuilder().add("codice", e.codice()).add("messaggio", e.getMessage()).build();
        return Response.status(e.statoHttp()).type(MediaType.APPLICATION_JSON)
                .header("Cache-Control","no-store").entity(dati).build();
    }
}
