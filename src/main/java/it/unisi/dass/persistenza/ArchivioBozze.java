package it.unisi.dass.persistenza;

import it.unisi.dass.analisi.AbbinamentoCatalogo;
import it.unisi.dass.analisi.CorrezioneBozza;
import it.unisi.dass.analisi.ErroreAnalisi;
import it.unisi.dass.servizi.AnteprimaPasto;
import jakarta.ejb.Stateless;
import jakarta.json.*;
import jakarta.persistence.*;
import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Gestisce il ciclo di vita delle bozze e il catalogo usato per correggerle o calcolarne l'anteprima.
 */
@Stateless
public class ArchivioBozze {
    @PersistenceContext(unitName = "dass")
    private EntityManager em;

    /** Restituisce il catalogo filtrato usando l'EntityManager iniettato dal container. */
    public JsonArray catalogo(String filtro) {
        return catalogo(em, filtro);
    }

    /** Legge le schede in ordine di nome e ID, applica il filtro e le converte in JSON. */
    private static JsonArray catalogo(EntityManager em, String filtro) {
        var lista = Json.createArrayBuilder();
        for (var v : em.createQuery("select v from VoceCatalogo v order by v.nome, v.id", VoceCatalogo.class).getResultList()) {
            if (!filtro.isBlank() && !AbbinamentoCatalogo.corrispondeAllaRicerca(v.nome, v.preparazione, filtro)) continue;
            lista.add(v.json());
        }
        return lista.build();
    }

    /**
     * Rimuove le bozze scadute e salva una nuova bozza vuota con foto e descrizione, valida per 24
     * ore.
     */
    public String crea(byte[] jpeg, String caption) {
        long ora = Instant.now().getEpochSecond();
        em.createQuery("delete from Bozza b where b.scadeEpochSecond <= :ora").setParameter("ora",ora).executeUpdate();
        var b = new Bozza();
        b.id = UUID.randomUUID().toString();
        b.foto = jpeg;
        b.caption = caption;
        b.scadeEpochSecond = ora + Duration.ofHours(24).toSeconds();
        b.proposta = Json.createObjectBuilder().add("statoAnalisi","da_compilare")
                .add("origineAnalisi","manuale").add("ingredienti",Json.createArrayBuilder())
                .add("dubbi",Json.createArrayBuilder()).build().toString();
        em.persist(b);
        return b.id;
    }

    /** Trova una bozza ancora valida, applicando il lock richiesto dal chiamante. */
    static Bozza cerca(EntityManager em, String id, LockModeType lock) {
        var b = em.find(Bozza.class, id, lock);
        return b != null && b.scadeEpochSecond > Instant.now().getEpochSecond() ? b : null;
    }

    /**
     * Blocca la bozza, abbina la proposta di Haiku al catalogo, la salva e restituisce il dettaglio
     * aggiornato.
     */
    public JsonObject completa(String id, JsonObject proposta) {
        var b = cerca(em, id, LockModeType.PESSIMISTIC_WRITE);
        if (b == null) return null;
        b.aggiornaProposta(AbbinamentoCatalogo.collega(proposta,catalogo(""),b.caption).toString());
        em.flush();
        return dettaglio(b);
    }

    /** Restituisce il dettaglio di una bozza valida senza modificare l'archivio. */
    public JsonObject leggi(String id) {
        var b = cerca(em, id, LockModeType.NONE);
        return b == null ? null : dettaglio(b);
    }

    /**
     * Rilegge la bozza sotto lock, verifica la revisione attesa e salva le righe validate
     * incrementando la revisione.
     */
    public JsonObject correggi(String id, long revisione, JsonArray righe) {
        var b = cerca(em, id, LockModeType.PESSIMISTIC_WRITE);
        if (b == null) return null;
        // Rileggere la versione dal database anche in presenza della cache condivisa JPA.
        em.refresh(b, LockModeType.PESSIMISTIC_WRITE);
        verificaRevisione(b, revisione);
        try (var reader = Json.createReader(new StringReader(b.proposta))) {
            var precedente = reader.readObject();
            var aggiornate = CorrezioneBozza.prepara(righe,precedente.getJsonArray("ingredienti"),catalogo(""));
            b.aggiornaProposta(Json.createObjectBuilder(precedente).add("ingredienti",aggiornate)
                    .add("statoAnalisi","da_confermare").build().toString());
        }
        em.flush();
        return dettaglio(b);
    }

    /** Restituisce i byte JPEG della bozza valida, oppure null se non è disponibile. */
    public byte[] foto(String id) {
        var b = cerca(em, id, LockModeType.NONE);
        return b == null ? null : b.foto;
    }

    /**
     * Rimuove la bozza sotto lock. Se è ancora valida ed è indicata una revisione, verifica che
     * corrisponda a quella salvata.
     */
    public void rimuovi(String id, Long revisione) {
        var b = em.find(Bozza.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (b == null) return;
        if (revisione != null && b.scadeEpochSecond > Instant.now().getEpochSecond()) verificaRevisione(b, revisione);
        em.remove(b);
    }

    /**
     * Carica il profilo dimostrativo e, per un piatto abituale, la memoria corrente. Calcola una
     * risposta senza registrare un pasto.
     */
    public JsonObject anteprima(String id, long revisione, JsonArray righe, String piattoId) {
        var b = cerca(em, id, LockModeType.NONE);
        if (b == null) return null;
        var paziente = em.find(Paziente.class, DatiDemo.PAZIENTE_ID);
        var memoria = piattoId == null ? null : ArchivioPasti.memoriaCorrente(em, piattoId);
        return calcola(em, b, revisione, righe, paziente, memoria);
    }

    /**
     * Controlla la revisione, valida le righe e richiama il calcolo condiviso da anteprima e
     * registrazione.
     */
    static JsonObject calcola(EntityManager em, Bozza b, long revisione, JsonArray righe,
                              Paziente paziente, MemoriaPiatto memoria) {
        verificaRevisione(b, revisione);
        var voci = catalogo(em, "");
        try (var reader = Json.createReader(new StringReader(b.proposta))) {
            var proposta = reader.readObject();
            var confermate = CorrezioneBozza.prepara(righe, proposta.getJsonArray("ingredienti"), voci);
            var risultato = AnteprimaPasto.calcola(confermate, voci, paziente.profilo(),
                    memoria == null ? null : memoria.perSimulazione());
            return Json.createObjectBuilder(risultato).add("origineAnalisi", proposta.getString("origineAnalisi", "manuale"))
                    .build();
        }
    }

    /** Segnala un conflitto HTTP 409 se la versione del client è superata. */
    private static void verificaRevisione(Bozza b, long revisione) {
        if (b.revisione != revisione) throw new ErroreAnalisi(409,"REVISIONE_SUPERATA",
                "La bozza è stata aggiornata in un’altra scheda. Riaprila prima di modificare.");
    }

    /**
     * Combina il JSON della proposta con ID, foto, revisione e scadenza. Ricalcola i suggerimenti per
     * le righe senza scheda e senza scelta manuale.
     */
    private JsonObject dettaglio(Bozza b) {
        try (var reader = Json.createReader(new StringReader(b.proposta))) {
            var proposta = reader.readObject();
            var dati = Json.createObjectBuilder(proposta);
            var righe = proposta.getJsonArray("ingredienti").getValuesAs(JsonObject.class);
            if (righe.stream().anyMatch(r -> r.getString("voceCatalogoId", null) == null &&
                    !r.getBoolean("sceltaCatalogoManuale", false))) {
                var voci = catalogo("");
                var conSuggerimenti = Json.createArrayBuilder();
                for (var r : righe) conSuggerimenti.add(AbbinamentoCatalogo.suggerisci(r, voci));
                dati.add("ingredienti", conSuggerimenti);
            }
            return dati.add("bozzaId",b.id).add("caption",b.caption)
                    .add("revisione",b.revisione).add("scadeAlle",Instant.ofEpochSecond(b.scadeEpochSecond).toString())
                    .add("fotoUrl","api/bozze/" + b.id + "/foto").build();
        }
    }
}
