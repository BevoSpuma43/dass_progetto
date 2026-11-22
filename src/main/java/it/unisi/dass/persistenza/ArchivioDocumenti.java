package it.unisi.dass.persistenza;

import it.unisi.dass.analisi.ErroreAnalisi;
import jakarta.ejb.Stateless;
import jakarta.json.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.util.Base64;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Archivio dei documenti cifrati. Controlla profilo, tipo, revisione e dimensioni, ma non legge mai il
 * contenuto: le regole del diario (bozze, pasti, calcolo del bolo) sono nel browser.
 */
@Stateless
public class ArchivioDocumenti {
    public static final Set<String> TIPI = Set.of("impostazioni", "bozza", "foto", "miniatura", "pasto");
    public static final int MASSIMO_DATI = 8 * 1024 * 1024;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @PersistenceContext(unitName = "dass")
    private EntityManager em;

    /** Profili disponibili, con il solo numero di documenti cifrati di ciascuno. */
    public JsonArray profili() {
        return profili(em);
    }

    /** Segnala HTTP 404 se il profilo non esiste. */
    public void verificaProfilo(String id) {
        profilo(em, id);
    }

    public JsonArray elenca(String profiloId, String tipo) {
        return elenca(em, profiloId, tipo);
    }

    public JsonObject leggi(String profiloId, String id) {
        return leggi(em, profiloId, id);
    }

    public JsonObject salva(String profiloId, String id, JsonObject richiesta) {
        return salva(em, profiloId, id, richiesta, System.currentTimeMillis());
    }

    public boolean elimina(String profiloId, String id, Long revisione) {
        return elimina(em, profiloId, id, revisione);
    }

    public JsonArray vista(int limite) {
        return vista(em, limite);
    }

    public JsonObject grezzo(String profiloId, String id) {
        return grezzo(em, profiloId, id);
    }

    /**
     * Vista di H2 per la verifica della cifratura: i documenti di tutti i profili, dal più recente, con
     * metadati ed estratto del testo cifrato. Payara non ha chiavi per decifrarli.
     */
    static JsonArray vista(EntityManager em, int limite) {
        var elenco = Json.createArrayBuilder();
        em.createQuery("select d from DocumentoCifrato d order by d.aggiornatoAlleEpochMilli desc, d.id", DocumentoCifrato.class)
                .setMaxResults(limite).getResultList().forEach(d -> elenco.add(d.riga()));
        return elenco.build();
    }

    /** Un documento completo così come è conservato, con il profilo proprietario. */
    static JsonObject grezzo(EntityManager em, String profiloId, String id) {
        profilo(em, profiloId);
        return documento(em, profiloId, id, LockModeType.NONE).grezzo();
    }

    static JsonArray profili(EntityManager em) {
        var elenco = Json.createArrayBuilder();
        for (var p : em.createQuery("select p from Profilo p order by p.id", Profilo.class).getResultList()) {
            long documenti = em.createQuery("select count(d) from DocumentoCifrato d where d.profilo.id = :id", Long.class)
                    .setParameter("id", p.id).getSingleResult();
            elenco.add(p.json(documenti));
        }
        return elenco.build();
    }

    static Profilo profilo(EntityManager em, String id) {
        var profilo = id == null || !ID.matcher(id).matches() ? null : em.find(Profilo.class, id);
        if (profilo == null) throw new ErroreAnalisi(404, "PROFILO_ASSENTE", "Profilo inesistente.");
        return profilo;
    }

    /** Documenti di un tipo, dal più recente: servono per elencare pasti, piatti e bozze senza scaricare le foto. */
    static JsonArray elenca(EntityManager em, String profiloId, String tipo) {
        profilo(em, profiloId);
        verificaTipo(tipo);
        var elenco = Json.createArrayBuilder();
        em.createQuery("select d from DocumentoCifrato d where d.profilo.id = :p and d.tipo = :t order by d.aggiornatoAlleEpochMilli desc, d.id",
                        DocumentoCifrato.class).setParameter("p", profiloId).setParameter("t", tipo).getResultList()
                .forEach(d -> elenco.add(d.json()));
        return elenco.build();
    }

    static JsonObject leggi(EntityManager em, String profiloId, String id) {
        profilo(em, profiloId);
        return documento(em, profiloId, id, LockModeType.NONE).json();
    }

    /**
     * Crea il documento con revisione attesa 0 oppure lo sostituisce se la revisione attesa coincide con
     * quella salvata. La revisione cresce a ogni scrittura e protegge dalle modifiche concorrenti.
     */
    static JsonObject salva(EntityManager em, String profiloId, String id, JsonObject richiesta, long ora) {
        verificaId(id);
        var profilo = profilo(em, profiloId);
        if (richiesta == null) throw invalido("Invia tipo, revisione, iv e dati.");
        String tipo = richiesta.getString("tipo", null);
        verificaTipo(tipo);
        if (!(richiesta.get("revisione") instanceof JsonNumber n) || !n.isIntegral() || n.longValue() < 0)
            throw invalido("Indica la revisione attesa del documento: 0 per crearlo.");
        long attesa = n.longValue();
        byte[] iv = base64(richiesta, "iv");
        byte[] dati = base64(richiesta, "dati");
        if (iv.length != 12) throw invalido("Il vettore di inizializzazione AES-GCM deve avere 12 byte.");
        // Il tag di autenticazione GCM occupa 16 byte: un testo cifrato più corto non è valido.
        if (dati.length < 16) throw invalido("Il testo cifrato è incompleto.");
        if (dati.length > MASSIMO_DATI) throw new ErroreAnalisi(413, "DOCUMENTO_GRANDE", "Il documento cifrato supera 8 MiB.");

        var documento = em.find(DocumentoCifrato.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (documento == null) {
            if (attesa != 0) throw new ErroreAnalisi(404, "DOCUMENTO_ASSENTE", "Il documento non esiste più.");
            documento = new DocumentoCifrato();
            documento.id = id;
            documento.profilo = profilo;
            documento.tipo = tipo;
            documento.revisione = 1;
            em.persist(documento);
        } else {
            if (!documento.profilo.id.equals(profiloId) || attesa == 0)
                throw new ErroreAnalisi(409, "DOCUMENTO_ESISTENTE", "Esiste già un documento con questo identificativo.");
            if (documento.revisione != attesa)
                throw new ErroreAnalisi(409, "REVISIONE_SUPERATA", "Il documento è stato aggiornato in un’altra scheda. Ricaricalo prima di modificarlo.");
            if (!documento.tipo.equals(tipo)) throw invalido("Il tipo di un documento non si può cambiare.");
            documento.revisione++;
        }
        documento.iv = iv;
        documento.dati = dati;
        documento.aggiornatoAlleEpochMilli = ora;
        return documento.ricevuta();
    }

    /** Elimina il documento del profilo; se è indicata una revisione, deve coincidere con quella salvata. */
    static boolean elimina(EntityManager em, String profiloId, String id, Long attesa) {
        verificaId(id);
        profilo(em, profiloId);
        var documento = em.find(DocumentoCifrato.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (documento == null || !documento.profilo.id.equals(profiloId)) return false;
        if (attesa != null && documento.revisione != attesa)
            throw new ErroreAnalisi(409, "REVISIONE_SUPERATA", "Il documento è stato aggiornato in un’altra scheda.");
        em.remove(documento);
        return true;
    }

    /** Un documento di un altro profilo risulta assente, come uno inesistente. */
    private static DocumentoCifrato documento(EntityManager em, String profiloId, String id, LockModeType blocco) {
        verificaId(id);
        var documento = em.find(DocumentoCifrato.class, id, blocco);
        if (documento == null || !documento.profilo.id.equals(profiloId))
            throw new ErroreAnalisi(404, "DOCUMENTO_ASSENTE", "Documento inesistente.");
        return documento;
    }

    private static void verificaId(String id) {
        if (id == null || !ID.matcher(id).matches()) throw invalido("Identificativo del documento non valido.");
    }

    private static void verificaTipo(String tipo) {
        if (tipo == null || !TIPI.contains(tipo)) throw invalido("Tipo di documento non valido. Ammessi: " + String.join(", ", TIPI.stream().sorted().toList()) + ".");
    }

    private static byte[] base64(JsonObject richiesta, String campo) {
        try {
            return Base64.getDecoder().decode(richiesta.getString(campo));
        } catch (RuntimeException e) {
            throw invalido("Il campo " + campo + " deve essere Base64.");
        }
    }

    private static ErroreAnalisi invalido(String messaggio) {
        return new ErroreAnalisi(400, "DOCUMENTO_NON_VALIDO", messaggio);
    }
}
