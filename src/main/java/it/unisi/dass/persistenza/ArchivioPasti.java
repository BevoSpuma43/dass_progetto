package it.unisi.dass.persistenza;

import it.unisi.dass.analisi.ErroreAnalisi;
import it.unisi.dass.analisi.FotoPiatto;
import it.unisi.dass.servizi.AnteprimaPasto;
import it.unisi.dass.servizi.RichiestaPasto;
import jakarta.ejb.Stateless;
import jakarta.json.*;
import jakarta.persistence.*;
import java.io.StringReader;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Gestisce la registrazione dei pasti, la lettura dello storico e il recupero delle immagini. */
@Stateless
public class ArchivioPasti {
    @PersistenceContext(unitName = "dass")
    private EntityManager em;

    /**
     * Serializza le conferme sul profilo dimostrativo e riconosce i retry dalla chiave e
     * dall'impronta. Per una conferma nuova ricalcola e salva episodio, foto, istantanea e ingredienti
     * nella stessa transazione.
     */
    public JsonObject registra(RichiestaPasto richiesta) {
        // Il lock del profilo dimostrativo serializza le conferme, anche se riguardano bozze diverse.
        // La chiave univoca dell'episodio protegge il vincolo anche a livello di database.
        var paziente = em.find(Paziente.class, DatiDemo.PAZIENTE_ID, LockModeType.PESSIMISTIC_WRITE);
        if (paziente == null) throw new ErroreAnalisi(503, "PROFILO_ASSENTE", "Profilo dimostrativo non disponibile.");
        String impronta = richiesta.impronta();
        var precedente = em.createQuery("select e from Episodio e where e.chiaveInvio = :chiave", Episodio.class)
                .setParameter("chiave", richiesta.richiestaId()).getResultStream().findFirst();
        if (precedente.isPresent()) {
            var episodio = precedente.get();
            if (!impronta.equals(episodio.improntaInvio))
                throw new ErroreAnalisi(409, "RICHIESTA_GIA_USATA", "La stessa chiave identifica un'altra conferma.");
            return registrato(episodio);
        }

        var bozza = ArchivioBozze.cerca(em, richiesta.bozzaId(), LockModeType.PESSIMISTIC_WRITE);
        if (bozza == null) throw new ErroreAnalisi(404, "BOZZA_ASSENTE", "Bozza scaduta o inesistente.");
        var memoria = richiesta.piattoId() == null ? null : memoriaCorrente(em, richiesta.piattoId());
        var calcolo = ArchivioBozze.calcola(em, bozza, richiesta.revisione(), richiesta.ingredienti(), paziente, memoria);
        if (!"completa".equals(calcolo.getString("stato"))) {
            String motivi = String.join(" ", calcolo.getJsonArray("daCompletare").getValuesAs(JsonString::getString));
            throw new ErroreAnalisi(400, "PASTO_INCOMPLETO", motivi);
        }

        var totali = calcolo.getJsonObject("totali");
        var macro = new Macronutrienti(numero(totali, "carboidratiG"), numero(totali, "proteineG"),
                numero(totali, "grassiG"), numero(totali, "kcal"));
        var miniatura = FotoPiatto.miniatura(bozza.foto);
        if (memoria == null) memoria = nuovoPiatto(paziente, richiesta.nomePiatto(), macro.carboidratiG);
        var simulazione = Json.createObjectBuilder(calcolo.getJsonObject("simulazione"))
                .add("memoriaId", memoria.id).add("revisioneMemoria", memoria.numero).build();

        var episodio = new Episodio();
        episodio.id = UUID.randomUUID().toString();
        episodio.piatto = memoria.piatto;
        episodio.memoriaOriginale = memoria;
        episodio.chiaveInvio = richiesta.richiestaId();
        episodio.improntaInvio = impronta;
        episodio.istantePastoEpochSecond = richiesta.consumatoAlle().getEpochSecond();
        episodio.caption = bozza.caption;
        episodio.formatoFoto = "image/jpeg";
        episodio.foto = bozza.foto.clone();
        episodio.miniatura = miniatura;
        episodio.totali = macro;
        episodio.stimaOriginale = numero(simulazione, "unitaSimulate");
        episodio.versioneProfilo = paziente.versioneProfilo;
        episodio.versioneRegola = calcolo.getString("versioneRegola");
        episodio.indiceGlicemico = "non_disponibile";
        episodio.anteprimaOriginale = Json.createObjectBuilder(calcolo).add("simulazione", simulazione).build().toString();
        em.persist(episodio);
        for (var riga : calcolo.getJsonArray("righe").getValuesAs(JsonObject.class)) {
            var ingrediente = new Ingrediente();
            ingrediente.id = UUID.randomUUID().toString();
            ingrediente.episodio = episodio;
            ingrediente.voce = em.getReference(VoceCatalogo.class, riga.getString("voceCatalogoId"));
            ingrediente.quantitaG = numero(riga, "quantitaG");
            ingrediente.origineQuantita = riga.getString("origineQuantita");
            em.persist(ingrediente);
        }
        return registrato(episodio);
    }

    /** Crea un piatto e la prima memoria usando i carboidrati confermati come valore iniziale. */
    private MemoriaPiatto nuovoPiatto(Paziente paziente, String nome, double carboidratiG) {
        var piatto = new Piatto();
        piatto.id = UUID.randomUUID().toString();
        piatto.paziente = paziente;
        piatto.nome = nome;
        piatto.memoriaInizialeG = carboidratiG;
        em.persist(piatto);
        var memoria = new MemoriaPiatto();
        memoria.id = piatto.id + "-v0";
        memoria.piatto = piatto;
        memoria.carboidratiEfficaciG = carboidratiG;
        memoria.varianza = AnteprimaPasto.VARIANZA_INIZIALE;
        em.persist(memoria);
        return memoria;
    }

    /**
     * Controlla che il piatto appartenga al profilo demo e seleziona la memoria con la revisione più
     * alta.
     */
    static MemoriaPiatto memoriaCorrente(EntityManager em, String piattoId) {
        var piatto = em.find(Piatto.class, piattoId);
        if (piatto == null || !DatiDemo.PAZIENTE_ID.equals(piatto.paziente.id)) throw piattoAssente();
        return em.createQuery("select m from MemoriaPiatto m where m.piatto.id = :id order by m.numero desc",
                        MemoriaPiatto.class).setParameter("id", piattoId).setMaxResults(1).getResultStream().findFirst()
                .orElseThrow(() -> new ErroreAnalisi(409, "MEMORIA_ASSENTE", "Il piatto abituale non ha una memoria iniziale."));
    }

    /** Elenca gli episodi di un piatto dal più recente e indica se ciascuno ha un feedback. */
    public JsonArray pastiDelPiatto(String id) {
        if (em.find(Piatto.class, id) == null) throw piattoAssente();
        var feedback = em.createQuery("select f.episodio.id from Feedback f where f.episodio.piatto.id = :id", String.class)
                .setParameter("id", id).getResultList();
        var lista = Json.createArrayBuilder();
        em.createQuery("select e from Episodio e where e.piatto.id = :id order by e.istantePastoEpochSecond desc, e.id",
                Episodio.class).setParameter("id", id).getResultList()
                .forEach(e -> lista.add(riepilogo(e, feedback.contains(e.id))));
        return lista.build();
    }

    /** Elenca i piatti di un profilo, associando l'ultimo episodio quando presente. */
    public JsonArray piattiDelPaziente(String id) {
        verificaPaziente(id);
        var ultimi = new LinkedHashMap<String, Episodio>();
        em.createQuery("select e from Episodio e where e.piatto.paziente.id = :id order by e.istantePastoEpochSecond desc, e.id",
                Episodio.class).setParameter("id", id).getResultList()
                .forEach(e -> ultimi.putIfAbsent(e.piatto.id, e));
        var feedback = em.createQuery("select f.episodio.id from Feedback f where f.episodio.piatto.paziente.id = :id",
                String.class).setParameter("id", id).getResultList();
        var piatti = Json.createArrayBuilder();
        ultimi.values().forEach(e -> piatti.add(riferimento(e.piatto).add("ultimoPasto", riepilogo(e, feedback.contains(e.id)))));
        em.createQuery("select p from Piatto p where p.paziente.id = :id order by p.nome, p.id", Piatto.class)
                .setParameter("id", id).getResultList().stream().filter(p -> !ultimi.containsKey(p.id))
                .forEach(p -> piatti.add(riferimento(p).addNull("ultimoPasto")));
        return piatti.build();
    }

    /**
     * Legge i valori salvati di tutti i pasti del profilo in ordine cronologico inverso, senza
     * caricare le immagini nel riepilogo.
     */
    public JsonArray pastiDelPaziente(String id) {
        verificaPaziente(id);
        var lista = Json.createArrayBuilder();
        // Restituisce i totali registrati senza leggere i BLOB di foto e anteprima.
        em.createQuery("""
                select e.id, e.piatto.nome, e.istantePastoEpochSecond, e.totali, e.stimaOriginale
                from Episodio e where e.piatto.paziente.id = :id
                order by e.istantePastoEpochSecond desc, e.id
                """, Object[].class).setParameter("id", id).getResultList().forEach(campi -> {
            String pastoId = (String) campi[0];
            lista.add(Json.createObjectBuilder().add("pastoId", pastoId).add("nomePiatto", (String) campi[1])
                    .add("consumatoAlle", Instant.ofEpochSecond((Long) campi[2]).toString())
                    .add("totali", ((Macronutrienti) campi[3]).json())
                    .add("stimaOriginale", (Double) campi[4])
                    .add("miniaturaUrl", "api/pasti/" + pastoId + "/foto?miniatura=true"));
        });
        return lista.build();
    }

    /** Segnala HTTP 404 se il profilo richiesto non esiste. */
    private void verificaPaziente(String id) {
        if (em.find(Paziente.class, id) == null)
            throw new ErroreAnalisi(404, "PAZIENTE_ASSENTE", "Paziente inesistente.");
    }

    /**
     * Legge l'istantanea originale del pasto. Per gli episodi demo che la precedono ricostruisce il
     * dettaglio dalle righe e dalla memoria salvate.
     */
    public JsonObject dettaglio(String id) {
        var e = cercaPasto(id);
        var feedback = em.createQuery("select f from Feedback f where f.episodio.id = :id", Feedback.class)
                .setParameter("id", id).getResultStream().findFirst();
        var risultato = riepilogo(e, feedback.isPresent()).add("caption", e.caption)
                .add("versioneRegola", e.versioneRegola).add("versioneProfilo", e.versioneProfilo)
                .add("indiceGlicemico", e.indiceGlicemico);
        risultato.add("feedback", feedback.<JsonValue>map(ArchivioPasti::lettura).orElse(JsonValue.NULL));
        if (e.anteprimaOriginale != null) {
            try (var reader = Json.createReader(new StringReader(e.anteprimaOriginale))) {
                var originale = reader.readObject();
                return risultato.add("ingredienti", originale.getJsonArray("righe")).add("totali", originale.getJsonObject("totali"))
                        .add("profilo", originale.getJsonObject("profilo")).add("simulazioneOriginale", originale.getJsonObject("simulazione"))
                        .add("origineAnalisi", originale.getString("origineAnalisi")).build();
            }
        }

        // Gli episodi della fixture precedono l'istantanea JSON e conservano i riferimenti originali.
        var righe = Json.createArrayBuilder();
        em.createQuery("select i from Ingrediente i where i.episodio.id = :id order by i.id", Ingrediente.class)
                .setParameter("id", id).getResultList().forEach(i -> righe.add(Json.createObjectBuilder().add("nome", i.voce.nome)
                        .add("stato", i.voce.preparazione).add("quantitaG", i.quantitaG).add("origineQuantita", i.origineQuantita)
                        .add("unita", i.voce.unita).add("voceCatalogoId", i.voce.id).add("catalogo", i.voce.json())));
        var m = e.memoriaOriginale;
        var simulazione = Json.createObjectBuilder().add("scenario", "fixture")
                .add("memoriaId", m.id).add("revisioneMemoria", m.numero)
                .add("memoriaInizialeG", e.piatto.memoriaInizialeG).add("memoriaCorrenteG", m.carboidratiEfficaciG)
                .add("varianzaCorrente", m.varianza).add("unitaSimulate", e.stimaOriginale);
        return risultato.add("ingredienti", righe).add("totali", e.totali.json()).add("origineAnalisi", "fixture")
                .add("profilo", Json.createObjectBuilder().add("id", e.piatto.paziente.id).add("versione", e.versioneProfilo))
                .add("simulazioneOriginale", simulazione).build();
    }

    /** Carica la foto del pasto o la miniatura, in base al parametro ricevuto. */
    public byte[] foto(String id, boolean miniatura) {
        var e = cercaPasto(id);
        return miniatura ? e.miniatura : e.foto;
    }

    /** Cerca un episodio per ID e usa HTTP 404 se non lo trova. */
    private Episodio cercaPasto(String id) {
        var episodio = em.find(Episodio.class, id);
        if (episodio == null) throw new ErroreAnalisi(404, "PASTO_ASSENTE", "Pasto inesistente.");
        return episodio;
    }

    /** Costruisce la ricevuta della registrazione con gli ID del pasto e del piatto. */
    private static JsonObject registrato(Episodio episodio) {
        return Json.createObjectBuilder().add("pastoId", episodio.id).add("piattoId", episodio.piatto.id).build();
    }

    /** Prepara il riferimento JSON minimo a un piatto, con ID e nome. */
    private static JsonObjectBuilder riferimento(Piatto piatto) {
        return Json.createObjectBuilder().add("piattoId", piatto.id).add("nome", piatto.nome);
    }

    /** Prepara una riga sintetica del diario con data, foto, stima salvata e stato del feedback. */
    private static JsonObjectBuilder riepilogo(Episodio e, boolean conFeedback) {
        String foto = "api/pasti/" + e.id + "/foto";
        return Json.createObjectBuilder().add("pastoId", e.id).add("piattoId", e.piatto.id).add("nomePiatto", e.piatto.nome)
                .add("consumatoAlle", Instant.ofEpochSecond(e.istantePastoEpochSecond).toString())
                .add("fotoUrl", foto).add("miniaturaUrl", foto + "?miniatura=true").add("stimaOriginale", e.stimaOriginale)
                .add("statoFeedback", conFeedback ? "registrato" : "in_attesa");
    }

    /**
     * Converte in JSON la lettura associata all'episodio, con valore, orario ed esito della
     * valutazione.
     */
    private static JsonObject lettura(Feedback f) {
        return Json.createObjectBuilder().add("glicemiaMgDl", f.glicemiaMgDl).add("minutiDopoPasto", f.minutiDopoPasto)
                .add("istanteLettura", Instant.ofEpochSecond(f.istanteLetturaEpochSecond).toString())
                .add("ammissibile", f.ammissibile).add("motivo", f.motivo).build();
    }

    /** Costruisce l'errore HTTP 404 per un piatto non disponibile. */
    private static ErroreAnalisi piattoAssente() {
        return new ErroreAnalisi(404, "PIATTO_ASSENTE", "Piatto inesistente.");
    }

    /**
     * Estrae un numero JSON, verificando che sia rappresentabile come valore finito prima del
     * salvataggio.
     */
    private static double numero(JsonObject oggetto, String campo) {
        double valore = oggetto.getJsonNumber(campo).doubleValue();
        if (!Double.isFinite(valore))
            throw new ErroreAnalisi(400, "QUANTITA_NON_VALIDA", "Riduci la quantità indicata per gli ingredienti.");
        return valore;
    }
}
