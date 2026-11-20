package it.unisi.dass.api;

import it.unisi.dass.persistenza.ArchivioBozze;
import jakarta.ejb.EJB;
import jakarta.json.JsonArray;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

/** Espone le voci del catalogo alimentare come JSON. */
@Path("/catalogo")
@Produces(MediaType.APPLICATION_JSON)
public class CatalogoResource {
    @EJB private ArchivioBozze archivio;

    /** Elenca le schede alimentari, applicando il filtro facoltativo passato come parametro q. */
    @GET
    public JsonArray elenco(@QueryParam("q") @DefaultValue("") String filtro) {
        return archivio.catalogo(filtro);
    }
}
