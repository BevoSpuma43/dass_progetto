package it.unisi.dass.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

/** Espone un controllo semplice della raggiungibilità del backend. */
@Path("/stato")
public class StatoResource {
    /** Restituisce il valore {@code stato: ok} per confermare che l'API risponde. */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, String> leggiStato() {
        return Map.of("stato", "ok");
    }
}
