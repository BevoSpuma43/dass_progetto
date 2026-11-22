package it.unisi.dass.api;

import it.unisi.dass.persistenza.ArchivioDocumenti;
import jakarta.ejb.EJB;
import jakarta.json.Json;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Vista della tabella DOCUMENTO_CIFRATO per dimostrare che H2 conserva solo testo cifrato. Mostra i
 * documenti di tutti i profili, ma passa dal canale cifrato come ogni azione che legge H2: il browser può
 * provare a decifrare un documento solo con la chiave del diario del profilo attivo.
 */
@Path("/archivio-h2")
@Produces(MediaType.APPLICATION_JSON)
public class ArchivioH2Resource {
    @EJB
    private ArchivioDocumenti archivio;

    /** I documenti più recenti con metadati, lunghezza ed estratto del testo cifrato. */
    @GET
    public Response vista(@QueryParam("limite") @DefaultValue("30") int limite) {
        return ProfiliResource.esegui(() -> Json.createObjectBuilder().add("tabella", "DOCUMENTO_CIFRATO")
                .add("documenti", archivio.vista(Math.max(1, Math.min(limite, 100)))).build(), Response.Status.OK);
    }

    /** Un documento completo, di qualunque profilo, esattamente come è conservato. */
    @GET
    @Path("{profilo}/{id}")
    public Response documento(@PathParam("profilo") String profilo, @PathParam("id") String id) {
        return ProfiliResource.esegui(() -> archivio.grezzo(profilo, id), Response.Status.OK);
    }
}
