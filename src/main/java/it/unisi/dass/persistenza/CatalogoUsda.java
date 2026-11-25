package it.unisi.dass.persistenza;

import jakarta.json.Json;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;

import java.util.HashSet;

/**
 * Importa le schede complete del catalogo italiano: inserisce quelle nuove e aggiorna nome, preparazione,
 * fonte e valori di quelle già presenti, così una nuova versione del file corregge anche i valori salvati
 * (per esempio le vecchie schede sintetiche di DASS, che mantengono l'id).
 */
final class CatalogoUsda {
    private static final String RISORSA = "/catalogo/catalogo_nutrizionale_it.json";

    /** Impedisce l'istanziazione di questa classe di utilità. */
    private CatalogoUsda() {}

    /**
     * Carica il catalogo confezionato nel WAR e, una sola volta per versione, inserisce le schede
     * valide o aggiorna le etichette esistenti.
     */
    static void importa(EntityManager em) {
        var input = CatalogoUsda.class.getResourceAsStream(RISORSA);
        if (input == null) throw new IllegalStateException("Manca la risorsa " + RISORSA);
        try (var reader = Json.createReader(input)) {
            var catalogo = reader.readObject();
            String versione = catalogo.getString("versione");
            String idCaricamento = versione + "-schede";
            if (em.find(CaricamentoDemo.class, idCaricamento) != null) return;

            var esistenti = new HashSet<>(em.createQuery("select v.id from VoceCatalogo v", String.class).getResultList());
            int importati = 0;
            for (var scheda : catalogo.getJsonArray("alimenti").getValuesAs(JsonObject.class)) {
                var valori = scheda.getJsonObject("per100g");
                if (!validi(valori, "carboidratiG", "proteineG", "grassiG", "kcal")) continue;
                if (esistenti.contains(scheda.getString("id"))) {
                    aggiornaScheda(em, scheda, valori);
                } else {
                    inserisciVoce(em, scheda, valori);
                }
                importati++;
            }
            if (importati == 0) throw new IllegalStateException("Il catalogo non contiene schede complete");

            var caricamento = new CaricamentoDemo();
            caricamento.id = idCaricamento;
            caricamento.versione = versione;
            em.persist(caricamento);
        }
    }

    /** Controlla che tutti i valori nutrizionali richiesti siano numeri finiti e non negativi. */
    private static boolean validi(JsonObject valori, String... chiavi) {
        for (String chiave : chiavi) {
            if (!(valori.get(chiave) instanceof JsonNumber n) || n.bigDecimalValue().signum() < 0 ||
                    !Double.isFinite(n.doubleValue())) return false;
        }
        return true;
    }

    /** Aggiorna nome, preparazione, fonte, versione e valori per 100 g di una scheda esistente, conservandone l'ID. */
    private static void aggiornaScheda(EntityManager em, JsonObject scheda, JsonObject valori) {
        // Aggiorna direttamente le colonne, anche con il change tracking di Payara.
        em.createQuery("update VoceCatalogo v set v.nome = :nome, v.preparazione = :preparazione, v.fonte = :fonte, "
                        + "v.versione = :versione, v.per100g.carboidratiG = :carboidrati, v.per100g.proteineG = :proteine, "
                        + "v.per100g.grassiG = :grassi, v.per100g.kcal = :kcal where v.id = :id")
                .setParameter("nome", scheda.getString("nome"))
                .setParameter("preparazione", scheda.getString("preparazione"))
                .setParameter("fonte", scheda.getString("fonte"))
                .setParameter("versione", scheda.getString("versione"))
                .setParameter("carboidrati", valori.getJsonNumber("carboidratiG").doubleValue())
                .setParameter("proteine", valori.getJsonNumber("proteineG").doubleValue())
                .setParameter("grassi", valori.getJsonNumber("grassiG").doubleValue())
                .setParameter("kcal", valori.getJsonNumber("kcal").doubleValue())
                .setParameter("id", scheda.getString("id"))
                .setFlushMode(FlushModeType.COMMIT)
                .executeUpdate();
    }

    /** Crea e persiste una nuova scheda a partire da metadati e macro per 100 g. */
    private static void inserisciVoce(EntityManager em, JsonObject scheda, JsonObject valori) {
        var voce = new VoceCatalogo();
        voce.id = scheda.getString("id");
        voce.nome = scheda.getString("nome");
        voce.preparazione = scheda.getString("preparazione");
        voce.unita = "g";
        voce.fonte = scheda.getString("fonte");
        voce.versione = scheda.getString("versione");
        voce.indiceGlicemico = "non_disponibile";
        voce.per100g = new Macronutrienti(valori.getJsonNumber("carboidratiG").doubleValue(),
                valori.getJsonNumber("proteineG").doubleValue(), valori.getJsonNumber("grassiG").doubleValue(),
                valori.getJsonNumber("kcal").doubleValue());
        em.persist(voce);
    }
}
