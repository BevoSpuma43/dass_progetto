package it.unisi.dass.api;

import it.unisi.dass.persistenza.ArchivioDemo;
import jakarta.ejb.EJB;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

/** Espone il riepilogo tecnico del database dimostrativo. */
@Path("/demo/archivio")
public class ArchivioResource {
    @EJB
    private ArchivioDemo archivio;

    /** Risponde con i conteggi di profili, piatti, pasti e feedback e con gli ID dei pasti. */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> riepilogo() {
        return archivio.riepilogo();
    }
}
