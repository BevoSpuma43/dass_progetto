package it.unisi.dass.persistenza;

import it.unisi.dass.analisi.ErroreAnalisi;
import it.unisi.dass.servizi.RichiestaPasto;
import jakarta.json.*;
import jakarta.persistence.*;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Controlla la registrazione a livello JPA, con il provider e il driver di Payara. */
public final class CollaudoPasti {
    private static final Map<String, String> CONFIG = Map.of(
            "jakarta.persistence.jdbc.driver", "org.h2.Driver",
            "jakarta.persistence.jdbc.url", "jdbc:h2:mem:dass-step06;DB_CLOSE_DELAY=-1",
            "jakarta.persistence.jdbc.user", "sa",
            "eclipselink.weaving", "false", "eclipselink.logging.level", "SEVERE");

    /** Impedisce l'istanziazione di questa classe di collaudo. */
    private CollaudoPasti() {}

    /**
     * Verifica registrazione in H2, retry concorrenti, storico, cancellazione della bozza e rilettura
     * di foto e istantanee.
     */
    public static void main(String[] args) throws Exception {
        byte[] primaFoto = fotoDemo("pasta-01");
        byte[] secondaFoto = fotoDemo("pasta-02");
        preparaArchivioPrecedente();
        JsonObject primo;
        JsonObject secondo;
        JsonObject conferma;
        JsonObject originale;
        try (var factory = Persistence.createEntityManagerFactory("dass-test", CONFIG)) {
            var esistente = leggi(factory, a -> a.dettaglio("pasta-03"));
            verifica(esistente.getJsonArray("ingredienti").size() == 3, "Righe della fixture");
            verifica(esistente.getJsonObject("feedback").getString("motivo").equals("aggiornamento_fixture"), "Feedback della fixture");
            verifica(Arrays.equals(primaFoto, leggi(factory, a -> a.foto("pasta-01", false))), "Foto della fixture");

            var primaBozza = bozza(factory, primaFoto);
            var secondaBozza = bozza(factory, secondaFoto);
            conferma = Json.createObjectBuilder().add("richiestaId", "invio-step06-01")
                    .add("bozzaId", primaBozza.getString("bozzaId")).add("revisione", primaBozza.getJsonNumber("revisione"))
                    .add("consumatoAlle", "2026-11-17T19:30:00+01:00").add("nomePiatto", "Pasta di collaudo")
                    .add("ingredienti", primaBozza.getJsonArray("ingredienti")).build();
            rifiuto(factory, modifica(conferma, "ingredienti", JsonValue.EMPTY_JSON_ARRAY), 400, "PASTO_INCOMPLETO");
            rifiuto(factory, modifica(conferma, "revisione", Json.createValue(0)), 409, "REVISIONE_SUPERATA");
            rifiuto(factory, modifica(conferma, "consumatoAlle", Json.createValue("2026-11-17T19:30:00")), 400, "ORARIO_NON_VALIDO");
            verifica(conteggi(factory).equals(List.of(2L, 5L, 14L, 3L, 3L)), "Un errore lascia intatti i record");

            primo = inviaInParallelo(factory, conferma);
            var stessoIstante = modifica(conferma, "consumatoAlle", Json.createValue("2026-11-17T18:30:00Z"));
            verifica(registra(factory, stessoIstante).equals(primo), "Riprova con offset equivalente");
            rifiuto(factory, modifica(conferma, "nomePiatto", Json.createValue("Altro nome")), 409, "RICHIESTA_GIA_USATA");
            originale = leggi(factory, a -> a.dettaglio(primo.getString("pastoId")));
            var olio = originale.getJsonArray("ingredienti").getJsonObject(1);
            verifica(olio.getString("origineQuantita").equals("ricetta") && olio.getString("fonteUrl").contains("/olio"),
                    "Provenienza della ricetta nell'episodio");
            verifica(olio.getJsonObject("catalogo").getString("versione").equals(DatiDemo.VERSIONE), "Versione catalogo");
            verifica(originale.getString("consumatoAlle").equals("2026-11-17T18:30:00Z"), "Istante UTC");
            verifica(originale.getJsonObject("simulazioneOriginale").getInt("revisioneMemoria") == 0, "Memoria iniziale");
            verifica(originale.isNull("feedback"), "Nuovo pasto in attesa del feedback");

            var modello = Json.createObjectBuilder(conferma).remove("nomePiatto")
                    .add("bozzaId", secondaBozza.getString("bozzaId")).add("ingredienti", secondaBozza.getJsonArray("ingredienti"))
                    .add("consumatoAlle", "2026-11-18T19:30:00+01:00").build();
            var confermaRicorrente = Json.createObjectBuilder(modello).add("piattoId", primo.getString("piattoId"))
                    .add("richiestaId", "invio-step06-02").build();
            secondo = registra(factory, confermaRicorrente);
            var confermaControllo = Json.createObjectBuilder(modello).add("piattoId", "piatto-controllo")
                    .add("richiestaId", "invio-step06-03").build();
            var controllo = registra(factory, confermaControllo);
            verifica(conteggi(factory).equals(List.of(3L, 8L, 20L, 4L, 3L)), "Tre episodi, un solo piatto nuovo e nessun feedback aggiunto");
            var isolamento = leggi(factory, a -> a.pastiDelPiatto("piatto-controllo"));
            verifica(isolamento.size() == 2 && isolamento.getJsonObject(0).getString("pastoId").equals(controllo.getString("pastoId")),
                    "Isolamento fra piatti");
            verifica(isolamento.getJsonObject(0).getJsonNumber("stimaOriginale").doubleValue() == 3.5,
                    "La ricorrenza usa la memoria del piatto scelto");

            var diario = leggi(factory, a -> a.pastiDelPaziente(DatiDemo.PAZIENTE_ID));
            verifica(diario.size() == 8, "Storico di tutti i piatti del paziente");
            verifica(diario.getJsonObject(0).getString("consumatoAlle").equals("2026-11-18T18:30:00Z"),
                    "Gli episodi più recenti sono all'inizio");
            var riepilogo = diario.getValuesAs(JsonObject.class).stream()
                    .filter(r -> r.getString("pastoId").equals(primo.getString("pastoId"))).findFirst().orElseThrow();
            var totali = riepilogo.getJsonObject("totali");
            var totaliOriginali = originale.getJsonObject("totali");
            verifica(totaliOriginali.keySet().stream().allMatch(k ->
                            totali.getJsonNumber(k).doubleValue() == totaliOriginali.getJsonNumber(k).doubleValue()) &&
                    riepilogo.getJsonNumber("stimaOriginale").doubleValue() == originale.getJsonNumber("stimaOriginale").doubleValue(),
                    "Lo storico mostra i valori salvati");
            String rimossa = secondaBozza.getString("bozzaId");
            long revisione = secondaBozza.getJsonNumber("revisione").longValueExact();
            try {
                inTransazione(factory, em -> {
                    collega(new ArchivioBozze(), em).rimuovi(rimossa, revisione - 1);
                    return null;
                });
                throw new AssertionError("Atteso conflitto di revisione");
            } catch (ErroreAnalisi e) {
                verifica(e.statoHttp() == 409, "Una revisione vecchia non rimuove la bozza");
            }
            inTransazione(factory, em -> {
                var servizio = collega(new ArchivioBozze(), em);
                servizio.rimuovi(rimossa, revisione);
                return null;
            });
            inTransazione(factory, em -> {
                var servizio = collega(new ArchivioBozze(), em);
                servizio.rimuovi(rimossa, revisione);
                verifica(servizio.leggi(rimossa) == null && servizio.foto(rimossa) == null, "Bozza e foto rimosse");
                return null;
            });
            verifica(registra(factory, confermaRicorrente).equals(secondo), "Il pasto è indipendente dalla bozza rimossa");

            try (var em = factory.createEntityManager()) {
                CollaudoPersistenza.transazione(em, () -> {
                    em.find(Bozza.class, primaBozza.getString("bozzaId")).scadeEpochSecond = 0;
                    em.find(VoceCatalogo.class, "olio-v1").fonte = "Fonte aggiornata dopo la registrazione";
                });
            }
        }

        try (var factory = Persistence.createEntityManagerFactory("dass-test", CONFIG)) {
            String id = primo.getString("pastoId");
            verifica(registra(factory, conferma).equals(primo), "Riprova dopo la scadenza della bozza");
            verifica(leggi(factory, a -> a.dettaglio(id)).equals(originale), "Istantanea ricaricata senza ricalcolare");
            var storico = leggi(factory, a -> a.pastiDelPiatto(primo.getString("piattoId")));
            verifica(storico.size() == 2 && storico.getJsonObject(0).getString("pastoId").equals(secondo.getString("pastoId"))
                    && storico.getJsonObject(1).getString("pastoId").equals(id), "Ordinamento dello storico");
            verifica(leggi(factory, a -> a.piattiDelPaziente(DatiDemo.PAZIENTE_ID)).size() == 3, "Elenco dei piatti");
            verifica(Arrays.equals(primaFoto, leggi(factory, a -> a.foto(id, false))), "Foto del primo pasto");
            verifica(Arrays.equals(secondaFoto, leggi(factory, a -> a.foto(secondo.getString("pastoId"), false))), "Foto del secondo pasto");
            byte[] miniatura = leggi(factory, a -> a.foto(id, true));
            var immagine = ImageIO.read(new ByteArrayInputStream(miniatura));
            verifica(Math.max(immagine.getWidth(), immagine.getHeight()) == 120, "Miniatura JPEG");
        }
        System.out.println("H2 in memoria: concorrenza, storico, rimozione bozze, foto e istantanea verificati.");
    }

    /**
     * Crea un database di prova con le fixture e riproduce lo schema precedente eliminando le due
     * colonne aggiunte dopo.
     */
    private static void preparaArchivioPrecedente() {
        try (var factory = Persistence.createEntityManagerFactory("dass-test", CONFIG);
             var em = factory.createEntityManager()) {
            CollaudoPersistenza.transazione(em, () -> DatiDemo.inizializza(em));
            // Riproduce il layout della tabella prima dello step 06, con i cinque episodi già presenti.
            CollaudoPersistenza.transazione(em, () -> {
                em.createNativeQuery("alter table EPISODIO drop column ANTEPRIMAORIGINALE").executeUpdate();
                em.createNativeQuery("alter table EPISODIO drop column IMPRONTAINVIO").executeUpdate();
            });
        }
    }

    /** Crea una bozza di collaudo con foto e una proposta simulata con cui provare la registrazione. */
    private static JsonObject bozza(EntityManagerFactory factory, byte[] foto) {
        String id = inTransazione(factory, em -> collega(new ArchivioBozze(), em).crea(foto, "80 g di pasta a crudo"));
        var proposta = Json.createObjectBuilder().add("origineAnalisi", "simulata")
                .add("ingredienti", Json.createArrayBuilder()
                        .add(Json.createObjectBuilder().add("nome", "Pasta").add("quantitaG", 80)
                                .add("origineQuantita", "caption").add("stato", "crudo"))
                        .add(Json.createObjectBuilder().add("nome", "Olio di oliva").add("quantitaG", 6)
                                .add("origineQuantita", "ricetta").add("stato", "crudo")
                                .add("fonteUrl", "https://example.org/olio").add("motivazione", "Dose della ricetta").add("fonteTitolo", "Olio")))
                .add("dubbi", JsonValue.EMPTY_JSON_ARRAY).build();
        return inTransazione(factory, em -> collega(new ArchivioBozze(), em).completa(id, proposta));
    }

    /** Invia la stessa conferma da due thread e verifica che entrambi ricevano gli stessi ID. */
    private static JsonObject inviaInParallelo(EntityManagerFactory factory, JsonObject conferma) throws Exception {
        var barriera = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var primo = executor.submit(() -> { barriera.await(10, TimeUnit.SECONDS); return registra(factory, conferma); });
            var duplicato = executor.submit(() -> { barriera.await(10, TimeUnit.SECONDS); return registra(factory, conferma); });
            var risultato = primo.get(15, TimeUnit.SECONDS);
            verifica(risultato.equals(duplicato.get(15, TimeUnit.SECONDS)), "Il doppio invio deve avere lo stesso ID");
            return risultato;
        }
    }

    /** Copia un JSON di conferma cambiando un solo campo per costruire una variante di prova. */
    private static JsonObject modifica(JsonObject richiesta, String chiave, JsonValue valore) {
        return Json.createObjectBuilder(richiesta).add(chiave, valore).build();
    }

    /** Valida e registra una conferma dentro una transazione di collaudo. */
    private static JsonObject registra(EntityManagerFactory factory, JsonObject richiesta) {
        return leggi(factory, a -> a.registra(RichiestaPasto.leggi(richiesta)));
    }

    /** Verifica che la registrazione sia rifiutata con stato HTTP e codice applicativo attesi. */
    private static void rifiuto(EntityManagerFactory factory, JsonObject richiesta, int http, String codice) {
        try {
            registra(factory, richiesta);
            throw new AssertionError("Atteso " + codice);
        } catch (ErroreAnalisi e) {
            verifica(e.statoHttp() == http && e.codice().equals(codice), "Risposta attesa: " + codice + ", ottenuta: " + e.codice());
        }
    }

    /** Conta le entità del diario per verificare quali record sono stati creati o lasciati intatti. */
    private static List<Long> conteggi(EntityManagerFactory factory) {
        try (var em = factory.createEntityManager()) {
            return List.of("Piatto", "Episodio", "Ingrediente", "MemoriaPiatto", "Feedback").stream()
                    .map(nome -> em.createQuery("select count(e) from " + nome + " e", Long.class).getSingleResult()).toList();
        }
    }

    /**
     * Fornisce un ArchivioPasti collegato al database di prova ed esegue l'azione indicata in
     * transazione.
     */
    private static <T> T leggi(EntityManagerFactory factory, Function<ArchivioPasti, T> azione) {
        return inTransazione(factory, em -> azione.apply(collega(new ArchivioPasti(), em)));
    }

    /**
     * Apre l'EntityManager di collaudo, conferma la transazione a buon fine e la annulla se rimane
     * attiva dopo un errore.
     */
    private static <T> T inTransazione(EntityManagerFactory factory, Function<EntityManager, T> azione) {
        try (var em = factory.createEntityManager()) {
            em.getTransaction().begin();
            try {
                T risultato = azione.apply(em);
                em.getTransaction().commit();
                return risultato;
            } finally {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
            }
        }
    }

    /** Inietta l'EntityManager tramite reflection per provare i servizi JPA fuori dal container. */
    private static <T> T collega(T servizio, EntityManager em) {
        try {
            var campo = servizio.getClass().getDeclaredField("em");
            campo.setAccessible(true);
            campo.set(servizio, em);
            return servizio;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("EntityManager del collaudo", e);
        }
    }

    /** Carica il JPEG della fixture usato per confrontare le immagini lette dal diario. */
    private static byte[] fotoDemo(String id) throws Exception {
        try (var risorsa = DatiDemo.class.getResourceAsStream("/demo/" + id + ".jpg")) {
            return risorsa.readAllBytes();
        }
    }

    /** Interrompe il collaudo con un messaggio specifico quando una condizione attesa è falsa. */
    private static void verifica(boolean atteso, String messaggio) {
        if (!atteso) throw new AssertionError(messaggio);
    }
}
