package it.unisi.dass.persistenza;

import it.unisi.dass.analisi.ErroreAnalisi;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.function.Function;

/**
 * Collaudo dell'archivio cifrato su un database H2 temporaneo: migrazione di un archivio che contiene
 * ancora paz-demo, profili vuoti, revisioni, isolamento tra profili e persistenza dopo il riavvio.
 */
public class CollaudoDocumenti {
    private static final byte[] IV = new byte[12];

    public static void main(String[] args) throws Exception {
        Path cartella = args.length > 0 ? Path.of(args[0]) : Files.createTempDirectory("collaudo-documenti-");
        String url = "jdbc:h2:file:" + cartella.resolve("dass").toAbsolutePath().toString().replace('\\', '/');
        creaArchivioPrecedente(url);

        var emf = apri(url);
        transazione(emf, em -> { AvvioArchivio.inizializza(em); return null; });
        verificaMigrazione(emf);
        transazione(emf, em -> { AvvioArchivio.inizializza(em); return null; });
        verificaMigrazione(emf);
        long voci = transazione(emf, em -> em.createQuery("select count(v) from VoceCatalogo v", Long.class).getSingleResult());
        verifica(voci >= 450, "catalogo importato (" + voci + " schede)");
        verificaCatalogo(emf);
        verificaDocumenti(emf);
        emf.close();

        // Dopo il riavvio i documenti cifrati ci sono ancora e paz-demo non ricompare.
        var riavvio = apri(url);
        transazione(riavvio, em -> { AvvioArchivio.inizializza(em); return null; });
        var letto = transazione(riavvio, em -> ArchivioDocumenti.leggi(em, "utente1", "p-persistente"));
        verifica(letto.getInt("revisione") == 2 && letto.getString("dati").equals(base64(dati(64, 9))), "documento conservato dopo il riavvio");
        verificaMigrazione(riavvio);
        riavvio.close();
        System.out.println("OK: rimozione di paz-demo, profili utente1 e utente2, documenti cifrati, revisioni, isolamento tra profili e riavvio.");
    }

    /** Un archivio della versione precedente: tabelle del diario in chiaro con il profilo paz-demo. */
    private static void creaArchivioPrecedente(String url) throws Exception {
        try (var connessione = java.sql.DriverManager.getConnection(url, "sa", ""); var istruzione = connessione.createStatement()) {
            istruzione.execute("CREATE TABLE PAZIENTE (ID VARCHAR(255) PRIMARY KEY, NOME VARCHAR(255))");
            istruzione.execute("INSERT INTO PAZIENTE VALUES ('paz-demo', 'Paziente dimostrativo')");
            istruzione.execute("CREATE TABLE PIATTO (ID VARCHAR(255) PRIMARY KEY, PAZIENTE_ID VARCHAR(255) REFERENCES PAZIENTE(ID), NOME VARCHAR(255))");
            istruzione.execute("INSERT INTO PIATTO VALUES ('piatto-pasta', 'paz-demo', 'Pasta al pomodoro')");
            istruzione.execute("CREATE TABLE EPISODIO (ID VARCHAR(255) PRIMARY KEY, PIATTO_ID VARCHAR(255) REFERENCES PIATTO(ID), CAPTION VARCHAR(500))");
            istruzione.execute("INSERT INTO EPISODIO VALUES ('pasta-01', 'piatto-pasta', 'Pasta al pomodoro con olio')");
            istruzione.execute("CREATE TABLE BOZZA (ID VARCHAR(255) PRIMARY KEY, CAPTION VARCHAR(500))");
        }
    }

    private static void verificaMigrazione(EntityManagerFactory emf) {
        long precedenti = transazione(emf, em -> ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME IN ('PAZIENTE','PIATTO','EPISODIO','BOZZA','INGREDIENTE','FEEDBACK','ESITO','MEMORIAPIATTO')")
                .getSingleResult()).longValue());
        verifica(precedenti == 0, "tabelle del diario in chiaro rimosse");
        var profili = transazione(emf, ArchivioDocumenti::profili);
        verifica(profili.size() == 2 && profili.getJsonObject(0).getString("id").equals("utente1")
                && profili.getJsonObject(1).getString("id").equals("utente2"), "solo utente1 e utente2");
        verifica(!profili.toString().contains("paz-demo"), "paz-demo non ricreato");
    }

    /**
     * Catalogo v2: carboidrati disponibili, niente schede sintetiche, e un archivio che ha ancora i vecchi
     * valori sintetici viene corretto mantenendo gli id.
     */
    private static void verificaCatalogo(EntityManagerFactory emf) {
        var pasta = transazione(emf, em -> em.find(VoceCatalogo.class, "pasta-v1").json());
        verifica(pasta.getString("nome").equals("Pasta di semola secca, cruda")
                && pasta.getJsonObject("per100g").getJsonNumber("carboidratiG").doubleValue() == 71.5, "pasta secca a 71,5 g di carboidrati");
        verifica(transazione(emf, em -> em.find(VoceCatalogo.class, "pasta-cotta-v1").per100g.carboidratiG) == 34.0, "pasta cotta CREA in carboidrati disponibili");
        long sintetiche = transazione(emf, em -> em.createQuery("select count(v) from VoceCatalogo v where v.fonte like 'DASS%'", Long.class).getSingleResult());
        verifica(sintetiche == 0, "nessuna scheda sintetica");

        // Un archivio della versione precedente: pasta-v1 sintetica e catalogo v2 non ancora importato.
        transazione(emf, em -> {
            em.createQuery("update VoceCatalogo v set v.nome = 'Pasta', v.fonte = 'DASS, valori sintetici per 100 g', "
                    + "v.per100g.carboidratiG = 60 where v.id = 'pasta-v1'").executeUpdate();
            em.remove(em.find(CaricamentoDemo.class, "catalogo-it-v2-schede"));
            return null;
        });
        transazione(emf, em -> { AvvioArchivio.inizializza(em); return null; });
        var corretta = transazione(emf, em -> em.find(VoceCatalogo.class, "pasta-v1").json());
        verifica(corretta.getString("nome").equals("Pasta di semola secca, cruda") && corretta.getString("fonte").startsWith("USDA")
                && corretta.getJsonObject("per100g").getJsonNumber("carboidratiG").doubleValue() == 71.5, "valori sintetici sostituiti all'avvio");
    }

    private static void verificaDocumenti(EntityManagerFactory emf) {
        verifica(transazione(emf, em -> ArchivioDocumenti.elenca(em, "utente1", "pasto")).isEmpty(), "diario di utente1 vuoto");
        verifica(transazione(emf, em -> ArchivioDocumenti.elenca(em, "utente2", "pasto")).isEmpty(), "diario di utente2 vuoto");

        var creato = transazione(emf, em -> ArchivioDocumenti.salva(em, "utente1", "p-persistente", documento("pasto", 0, dati(48, 7)), 1000));
        verifica(creato.getInt("revisione") == 1 && !creato.containsKey("dati"), "creazione con revisione 1");
        errore("DOCUMENTO_ESISTENTE", 409, () -> transazione(emf, em -> ArchivioDocumenti.salva(em, "utente1", "p-persistente", documento("pasto", 0, dati(48, 7)), 1001)));
        errore("REVISIONE_SUPERATA", 409, () -> transazione(emf, em -> ArchivioDocumenti.salva(em, "utente1", "p-persistente", documento("pasto", 5, dati(48, 7)), 1002)));
        var aggiornato = transazione(emf, em -> ArchivioDocumenti.salva(em, "utente1", "p-persistente", documento("pasto", 1, dati(64, 9)), 2000));
        verifica(aggiornato.getInt("revisione") == 2, "aggiornamento con revisione 2");

        var letto = transazione(emf, em -> ArchivioDocumenti.leggi(em, "utente1", "p-persistente"));
        verifica(letto.getString("dati").equals(base64(dati(64, 9))) && letto.getString("iv").equals(base64(IV)), "Payara conserva i byte cifrati senza modificarli");
        verifica(transazione(emf, em -> ArchivioDocumenti.elenca(em, "utente1", "pasto")).size() == 1, "elenco dei pasti di utente1");
        verifica(transazione(emf, em -> ArchivioDocumenti.elenca(em, "utente1", "foto")).isEmpty(), "elenco filtrato per tipo");

        // Isolamento: utente2 non vede né sovrascrive i documenti di utente1.
        verifica(transazione(emf, em -> ArchivioDocumenti.elenca(em, "utente2", "pasto")).isEmpty(), "il diario di utente2 resta vuoto");
        errore("DOCUMENTO_ASSENTE", 404, () -> transazione(emf, em -> ArchivioDocumenti.leggi(em, "utente2", "p-persistente")));
        errore("DOCUMENTO_ESISTENTE", 409, () -> transazione(emf, em -> ArchivioDocumenti.salva(em, "utente2", "p-persistente", documento("pasto", 2, dati(48, 1)), 3000)));
        verifica(!transazione(emf, em -> ArchivioDocumenti.elimina(em, "utente2", "p-persistente", null)), "utente2 non elimina il pasto di utente1");

        // Vista di H2: metadati ed estratto cifrato, mai il contenuto completo nell'elenco.
        var riga = transazione(emf, em -> ArchivioDocumenti.vista(em, 10)).getJsonObject(0);
        verifica(riga.getString("profilo").equals("utente1") && riga.getString("id").equals("p-persistente") && riga.getInt("byte") == 64
                && !riga.containsKey("dati") && riga.getString("estratto").equals(base64(Arrays.copyOf(dati(64, 9), 48))), "vista di H2");
        var grezzo = transazione(emf, em -> ArchivioDocumenti.grezzo(em, "utente1", "p-persistente"));
        verifica(grezzo.getString("profilo").equals("utente1") && grezzo.getString("dati").equals(base64(dati(64, 9))), "documento grezzo con il profilo");
        errore("DOCUMENTO_ASSENTE", 404, () -> transazione(emf, em -> ArchivioDocumenti.grezzo(em, "utente2", "p-persistente")));

        errore("DOCUMENTO_NON_VALIDO", 400, () -> transazione(emf, em -> ArchivioDocumenti.salva(em, "utente1", "x", documento("ricetta", 0, dati(32, 1)), 1)));
        errore("DOCUMENTO_NON_VALIDO", 400, () -> transazione(emf, em -> ArchivioDocumenti.salva(em, "utente1", "x", documento("pasto", 0, dati(8, 1)), 1)));
        errore("DOCUMENTO_NON_VALIDO", 400, () -> transazione(emf, em -> ArchivioDocumenti.salva(em, "utente1", "../x", documento("pasto", 0, dati(32, 1)), 1)));
        errore("DOCUMENTO_NON_VALIDO", 400, () -> transazione(emf, em -> ArchivioDocumenti.salva(em, "utente1", "p-persistente", documento("foto", 2, dati(32, 1)), 1)));
        errore("PROFILO_ASSENTE", 404, () -> transazione(emf, em -> ArchivioDocumenti.elenca(em, "paz-demo", "pasto")));
        errore("DOCUMENTO_GRANDE", 413, () -> transazione(emf, em -> ArchivioDocumenti.salva(em, "utente1", "grande", documento("foto", 0, dati(ArchivioDocumenti.MASSIMO_DATI + 1, 1)), 1)));

        transazione(emf, em -> ArchivioDocumenti.salva(em, "utente1", "temporaneo", documento("bozza", 0, dati(32, 3)), 4000));
        errore("REVISIONE_SUPERATA", 409, () -> transazione(emf, em -> ArchivioDocumenti.elimina(em, "utente1", "temporaneo", 7L)));
        verifica(transazione(emf, em -> ArchivioDocumenti.elimina(em, "utente1", "temporaneo", 1L)), "eliminazione con la revisione corrente");
        verifica(!transazione(emf, em -> ArchivioDocumenti.elimina(em, "utente1", "temporaneo", null)), "eliminazione ripetuta senza errori");
        var profili = transazione(emf, ArchivioDocumenti::profili);
        verifica(profili.getJsonObject(0).getInt("documenti") == 1 && profili.getJsonObject(1).getInt("documenti") == 0, "conteggio dei documenti per profilo");
    }

    private static JsonObject documento(String tipo, long revisione, byte[] dati) {
        return Json.createObjectBuilder().add("tipo", tipo).add("revisione", revisione).add("iv", base64(IV)).add("dati", base64(dati)).build();
    }

    private static byte[] dati(int lunghezza, int seme) {
        byte[] b = new byte[lunghezza];
        for (int i = 0; i < lunghezza; i++) b[i] = (byte) (i * 31 + seme);
        return b;
    }

    private static String base64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    private static EntityManagerFactory apri(String url) {
        return Persistence.createEntityManagerFactory("dass-test", Map.of(
                "jakarta.persistence.jdbc.driver", "org.h2.Driver", "jakarta.persistence.jdbc.url", url,
                "jakarta.persistence.jdbc.user", "sa", "jakarta.persistence.jdbc.password", ""));
    }

    /** Esegue il lavoro in una transazione locale; un errore applicativo la annulla. */
    private static <T> T transazione(EntityManagerFactory emf, Function<EntityManager, T> lavoro) {
        var em = emf.createEntityManager();
        try {
            em.getTransaction().begin();
            T risultato = lavoro.apply(em);
            em.getTransaction().commit();
            return risultato;
        } catch (RuntimeException e) {
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            throw e;
        } finally {
            em.close();
        }
    }

    @FunctionalInterface
    private interface Prova {
        void esegui();
    }

    private static void errore(String codice, int stato, Prova prova) {
        try {
            prova.esegui();
            throw new AssertionError("Atteso errore " + codice);
        } catch (ErroreAnalisi e) {
            verifica(e.codice().equals(codice) && e.statoHttp() == stato, "atteso " + codice + ", ottenuto " + e.codice());
        }
    }

    private static void verifica(boolean ok, String cosa) {
        if (!ok) throw new AssertionError("Verifica fallita: " + cosa);
    }
}
