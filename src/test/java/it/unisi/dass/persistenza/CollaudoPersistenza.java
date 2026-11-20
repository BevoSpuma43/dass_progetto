package it.unisi.dass.persistenza;

import it.unisi.dass.analisi.AbbinamentoCatalogo;
import it.unisi.dass.analisi.CorrezioneBozza;
import it.unisi.dass.servizi.AnteprimaPasto;
import jakarta.json.Json;
import jakarta.json.JsonValue;
import jakarta.persistence.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;

/** Verifica un archivio H2 su file in più avvii, incluse le migrazioni delle schede nutrizionali. */
public class CollaudoPersistenza {
    static final List<String> IDS = List.of("controllo-01", "pasta-01", "pasta-02", "pasta-03", "pasta-04");
    static final String MODIFICA = "Caption modificata dopo la prima inizializzazione";

    /**
     * Apre l'archivio temporaneo e avvia la fase richiesta, verificando inizializzazione, migrazione o
     * rilettura.
     */
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]).toRealPath();
        int avvio = Integer.parseInt(args[1]);
        controlla(Files.isRegularFile(dir.resolve("solo-collaudo")), "Directory di test non valida");
        String url = "jdbc:h2:file:" + dir.resolve("dass").toString().replace('\\', '/');
        var proprieta = Map.of("jakarta.persistence.jdbc.driver", "org.h2.Driver",
                "jakarta.persistence.jdbc.url", url, "jakarta.persistence.jdbc.user", "sa",
                "eclipselink.weaving", "false", "eclipselink.logging.level", "SEVERE");
        try (var factory = Persistence.createEntityManagerFactory("dass-test", proprieta);
             var em = factory.createEntityManager()) {
            if (avvio > 1) controlla(MODIFICA.equals(em.find(Episodio.class, "pasta-04").caption),
                    "La modifica salvata nella JVM precedente non si ritrova");
            transazione(em, () -> DatiDemo.inizializza(em));
            if (avvio == 1) {
                transazione(em, () -> {
                    em.find(Episodio.class, "pasta-04").caption = MODIFICA;
                    var latte = new VoceCatalogo();
                    latte.id = "usda-fdc-746782-2026-10-29";
                    latte.nome = "Milk, whole, 3.25% milkfat, with added vitamin D";
                    latte.preparazione = "non_noto";
                    latte.unita = "g";
                    latte.fonte = "USDA Foundation Foods, FDC ID 746782";
                    latte.versione = "usda-foundation-2026-10-29";
                    latte.indiceGlicemico = "non_disponibile";
                    latte.per100g = new Macronutrienti(4.63, 3.27, 3.2, 60);
                    em.persist(latte);
                    var importazionePrecedente = new CaricamentoDemo();
                    importazionePrecedente.id = latte.versione;
                    importazionePrecedente.versione = latte.versione;
                    em.persist(importazionePrecedente);
                    // La prima migrazione può risultare completata pur avendo lasciato questa etichetta in inglese.
                    var primaTraduzione = new CaricamentoDemo();
                    primaTraduzione.id = "catalogo-it-v1";
                    primaTraduzione.versione = "catalogo-it-v1";
                    em.persist(primaTraduzione);
                });
                provaRollback(em);
                provaVincoli(em);
            } else {
                transazione(em, () -> CatalogoUsda.importa(em));
            }
            em.clear();
            verificaArchivio(em, avvio > 1);
            controlla(MODIFICA.equals(em.find(Episodio.class, "pasta-04").caption), "Seed sovrascritto");
            System.out.println("Avvio " + avvio + " OK | 5 episodi con immagini, catalogo da " +
                    (avvio > 1 ? 375 : 7) + " voci.");
        }
    }

    /** Controlla conteggi, riferimenti, foto, miniature e valori storici previsti dalle fixture. */
    static void verificaArchivio(EntityManager em, boolean conUsda) throws Exception {
        var attesi = Map.of("Paziente", 1, "Piatto", 2, "Episodio", 5, "Ingrediente", 14,
                "VoceCatalogo", conUsda ? 375 : 7, "Feedback", 3, "Esito", 3, "MemoriaPiatto", 3,
                "CaricamentoDemo", conUsda ? 4 : 3);
        for (var voce : attesi.entrySet()) {
            var totale = em.createQuery("select count(x) from " + voce.getKey() + " x", Long.class).getSingleResult();
            controlla(totale == voce.getValue().longValue(), "Conteggio errato: " + voce.getKey());
        }
        if (conUsda) {
            verificaFarina(em);
            verificaCroissant(em);
        }
        var episodi = em.createQuery("select e from Episodio e order by e.id", Episodio.class).getResultList();
        controlla(IDS.equals(episodi.stream().map(e -> e.id).toList()), "ID diversi");
        Set<String> foto = new HashSet<>();
        for (Episodio e : episodi) {
            controlla(e.piatto.paziente.id.equals("paz-demo"), "Paziente diverso");
            controlla(e.memoriaOriginale.piatto.id.equals(e.piatto.id), "Memoria di un altro piatto");
            for (boolean mini : List.of(false, true)) {
                String file = e.id + (mini ? "-mini.jpg" : ".jpg");
                byte[] bytes = mini ? e.miniatura : e.foto;
                try (var atteso = DatiDemo.class.getResourceAsStream("/demo/" + file)) {
                    controlla(Arrays.equals(bytes, atteso.readAllBytes()), "Immagine diversa: " + file);
                }
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
                controlla(img != null && (mini ? img.getWidth() == 120 : img.getWidth() > 120), "Formato " + file);
            }
            controlla(foto.add(Base64.getEncoder().encodeToString(e.foto)), "Foto duplicate");
        }
        for (var f : em.createQuery("select f from Feedback f", Feedback.class).getResultList())
            controlla(f.istanteLetturaEpochSecond == f.episodio.istantePastoEpochSecond + 60L * f.minutiDopoPasto,
                    "Istante lettura incoerente");
        var esito = em.find(Esito.class, "esito-pasta-03");
        controlla(esito.feedback.ammissibile && esito.precedente.carboidratiEfficaciG == 50 &&
                esito.successiva.carboidratiEfficaciG == 52, "Transizione fixture");
        controlla(em.find(Episodio.class, "pasta-03").stimaOriginale == 5 &&
                em.find(Episodio.class, "pasta-04").stimaOriginale == 5.2, "Stime originali");
        controlla(em.createQuery("select count(f) from Feedback f where f.episodio.id='pasta-04'", Long.class)
                .getSingleResult() == 0, "Quarto episodio in attesa");
    }

    /** Verifica la scheda Farina 00, l'abbinamento per sinonimo e il calcolo dei valori per 50 g. */
    private static void verificaFarina(EntityManager em) {
        var farina = em.find(VoceCatalogo.class, "usda-fdc-2003586-2026-10-29");
        controlla(farina != null && farina.nome.equals("Farina 00") && farina.preparazione.equals("crudo") &&
                farina.per100g.carboidratiG == 74.4 && farina.per100g.proteineG == 11.4 &&
                farina.per100g.grassiG == 1.52 && farina.per100g.kcal == 366, "Scheda USDA della farina");
        var catalogo = Json.createArrayBuilder().add(farina.json()).build();
        var proposta = Json.createObjectBuilder().add("ingredienti", Json.createArrayBuilder().add(
                Json.createObjectBuilder().add("nome", "Farina di grano tenero tipo 00").add("stato", "crudo")
                        .add("quantitaG", 50).add("origineQuantita", "stimata"))).build();
        var ingredienti = AbbinamentoCatalogo.collega(proposta, catalogo, "croissant vuoto").getJsonArray("ingredienti");
        controlla(ingredienti.getJsonObject(0).getString("voceCatalogoId").equals(farina.id), "Abbinamento farina 00");
        var anteprima = AnteprimaPasto.calcola(ingredienti, catalogo, JsonValue.EMPTY_JSON_OBJECT, null);
        controlla(anteprima.getString("stato").equals("completa"), "Anteprima della farina incompleta");
        var totali = anteprima.getJsonObject("totali");
        controlla(totali.getJsonNumber("carboidratiG").doubleValue() == 37.2 && totali.getInt("kcal") == 183,
                "Calcolo per 50 g di farina 00");
    }

    /**
     * Verifica migrazione delle etichette e abbinamento degli ingredienti di un croissant, incluse le
     * scelte manuali.
     */
    private static void verificaCroissant(EntityManager em) {
        var latte = em.find(VoceCatalogo.class, "usda-fdc-746782-2026-10-29");
        controlla(latte.nome.startsWith("Latte intero") && latte.per100g.grassiG == 3.2, "Migrazione etichetta latte");
        var voci = Json.createArrayBuilder();
        em.createQuery("select v from VoceCatalogo v", VoceCatalogo.class).getResultList()
                .forEach(voce -> voci.add(voce.json()));
        var catalogo = voci.build();
        var ingredienti = Json.createArrayBuilder();
        var nomi = List.of("Farina 0 forte", "Latte", "Burro (impasto base)", "Burro (sfogliatura/lamelle)",
                "Zucchero di canna chiaro (impasto)", "Zucchero semolato (superficie/guarnizione)",
                "Uovo (spennellatura)", "Lievito di birra secco", "Sale");
        var ids = List.of("crea-000230-v1", latte.id, "crea-190010-v1", "crea-190010-v1",
                "italia-zuccheri-canna-grezzo-v1", "usda-fdc-746784-2026-10-29",
                "usda-fdc-748967-2026-10-29", "usda-sr-175043-v1", "usda-sr-173468-v1");
        for (String nome : nomi) {
            var riga = Json.createObjectBuilder().add("nome", nome);
            if (nome.startsWith("Zucchero semolato")) {
                riga.add("stato", "crudo").add("origineQuantita", "da_chiarire").addNull("quantitaG");
            } else {
                riga.add("stato", "cotto").add("origineQuantita", "ricetta").add("quantitaG", 10)
                        .add("motivazione", "Dose da ricetta divisa per le porzioni");
            }
            ingredienti.add(riga);
        }
        var proposta = Json.createObjectBuilder().add("ingredienti", ingredienti).build();
        var abbinati = AbbinamentoCatalogo.collega(proposta, catalogo, "croissant").getJsonArray("ingredienti");
        for (int i = 0; i < ids.size(); i++) {
            controlla(abbinati.getJsonObject(i).getString("voceCatalogoId", "").equals(ids.get(i)), "Abbinamento " + nomi.get(i));
        }
        controlla(abbinati.getJsonObject(5).isNull("quantitaG"), "La scelta della scheda non inventa il peso");
        controlla(AbbinamentoCatalogo.corrispondeAllaRicerca("Farina 0", "crudo", "farina 0 forte") &&
                !AbbinamentoCatalogo.corrispondeAllaRicerca("Farina 00", "crudo", "farina 0"), "Ricerca farina 0 distinta da 00");
        var farina = abbinati.getJsonObject(0);
        var precedente = Json.createObjectBuilder(farina).addNull("voceCatalogoId").remove("notaAbbinamento").build();
        var confermata = CorrezioneBozza.prepara(Json.createArrayBuilder().add(farina).build(),
                Json.createArrayBuilder().add(precedente).build(), catalogo).getJsonObject(0);
        controlla(confermata.containsKey("notaAbbinamento") && confermata.getString("origineQuantita").equals("ricetta"),
                "La conferma conserva la nota e la provenienza della dose");
        var rifiutata = Json.createObjectBuilder(farina).addNull("voceCatalogoId").add("sceltaCatalogoManuale", true).build();
        var salvata = CorrezioneBozza.prepara(Json.createArrayBuilder().add(rifiutata).build(),
                Json.createArrayBuilder().add(precedente).build(), catalogo).getJsonObject(0);
        controlla(AbbinamentoCatalogo.suggerisci(salvata, catalogo).isNull("voceCatalogoId"), "Scelta manuale conservata");
    }

    /**
     * Provoca un conflitto di unicità dopo aver scritto più entità e verifica che il rollback annulli
     * tutte le scritture.
     */
    static void provaRollback(EntityManager em) {
        // Inserisce tre righe reali, forza il flush e provoca un errore SQL prima del commit.
        boolean rifiutato = false;
        try {
            transazione(em, () -> {
                var e = em.find(Episodio.class, "pasta-02");
                em.detach(e);
                e.id = "episodio-rollback";
                e.chiaveInvio = "invio-rollback";
                em.persist(e);
                var r = new Ingrediente();
                r.id = "riga-rollback";
                r.episodio = e;
                r.voce = em.find(VoceCatalogo.class, "pasta-v1");
                r.origineQuantita = "test";
                r.quantitaG = 80;
                em.persist(r);
                var f = new Feedback();
                f.id = "lettura-rollback";
                f.episodio = e;
                f.motivo = "test";
                em.persist(f);
                em.flush();
                em.createNativeQuery("update EPISODIO set CHIAVEINVIO='invio-pasta-01' where ID='episodio-rollback'")
                        .executeUpdate();
            });
        } catch (PersistenceException e) { rifiutato = erroreSql(e, "23505"); }
        em.clear();
        controlla(rifiutato && em.find(Episodio.class, "episodio-rollback") == null &&
                em.find(Ingrediente.class, "riga-rollback") == null &&
                em.find(Feedback.class, "lettura-rollback") == null, "Rollback incompleto");
    }

    /**
     * Verifica che H2 rifiuti un feedback duplicato e un ingrediente con riferimento a un episodio
     * assente.
     */
    static void provaVincoli(EntityManager em) {
        for (String sql : List.of("update FEEDBACK set EPISODIO_ID='pasta-01' where ID='lettura-pasta-02'",
                "update INGREDIENTE set EPISODIO_ID='inesistente' where ID='pasta-01-pasta-v1'")) {
            boolean rifiutato = false;
            try { transazione(em, () -> em.createNativeQuery(sql).executeUpdate()); }
            catch (PersistenceException e) { rifiutato = erroreSql(e, sql.contains("FEEDBACK") ? "23505" : "23506"); }
            controlla(rifiutato, "Vincolo assente: " + sql);
        }
    }

    /** Cerca nella catena delle cause un'eccezione SQL con lo stato atteso. */
    static boolean erroreSql(Throwable causa, String stato) {
        while (causa != null) {
            if (causa instanceof java.sql.SQLException sql && stato.equals(sql.getSQLState())) return true;
            causa = causa.getCause();
        }
        return false;
    }

    /**
     * Esegue un'azione in una transazione di prova, con commit a buon fine e rollback se resta attiva.
     */
    static void transazione(EntityManager em, Runnable azione) {
        var tx = em.getTransaction();
        tx.begin();
        try {
            azione.run();
            tx.commit();
        } finally {
            if (tx.isActive()) tx.rollback();
        }
    }

    /** Interrompe il collaudo con il messaggio indicato quando il controllo fallisce. */
    static void controlla(boolean condizione, String errore) {
        if (!condizione) throw new AssertionError(errore);
    }
}


