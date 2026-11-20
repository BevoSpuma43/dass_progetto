package it.unisi.dass.servizi;

import jakarta.json.*;
import java.math.BigDecimal;
import java.util.HashMap;

/** Calcolo in sola lettura per il solo scenario didattico. */
public final class AnteprimaPasto {
    public static final String REGOLA = "demo-sintetica-v1";
    public static final int VARIANZA_INIZIALE = 100;

    /** Dati di una versione della memoria necessari per simulare un piatto abituale. */
    public record Memoria(String id, int revisione, double inizialeG, double correnteG, double varianza) {}

    /** Impedisce l'istanziazione di questa classe di utilità. */
    private AnteprimaPasto() {}

    /** Rappresenta i valori nutrizionali con BigDecimal durante il calcolo. */
    private record Macro(BigDecimal carboidrati, BigDecimal proteine, BigDecimal grassi, BigDecimal kcal) {
        /** Scala i valori per 100 g in base al peso in grammi confermato per una singola riga. */
        static Macro contributo(JsonObject per100g, BigDecimal peso) {
            BigDecimal fattore = peso.movePointLeft(2);
            return new Macro(per100g.getJsonNumber("carboidratiG").bigDecimalValue().multiply(fattore),
                    per100g.getJsonNumber("proteineG").bigDecimalValue().multiply(fattore),
                    per100g.getJsonNumber("grassiG").bigDecimalValue().multiply(fattore),
                    per100g.getJsonNumber("kcal").bigDecimalValue().multiply(fattore));
        }

        /**
         * Somma due contributi nutrizionali mantenendo separati carboidrati, proteine, grassi ed
         * energia.
         */
        Macro somma(Macro altra) {
            return new Macro(carboidrati.add(altra.carboidrati), proteine.add(altra.proteine),
                    grassi.add(altra.grassi), kcal.add(altra.kcal));
        }

        /** Converte i valori del calcolo nel formato JSON dell'anteprima. */
        JsonObject json() {
            return Json.createObjectBuilder().add("carboidratiG", carboidrati).add("proteineG", proteine)
                    .add("grassiG", grassi).add("kcal", kcal).build();
        }
    }

    /**
     * Calcola contributi e totali quando tutte le righe sono complete. Per la simulazione usa i
     * carboidrati confermati o la memoria del piatto abituale.
     */
    public static JsonObject calcola(JsonArray ingredienti, JsonArray catalogo, JsonObject profilo, Memoria memoria) {
        var voci = new HashMap<String, JsonObject>();
        catalogo.getValuesAs(JsonObject.class).forEach(v -> voci.put(v.getString("id"), v));
        var righe = Json.createArrayBuilder();
        var mancanti = Json.createArrayBuilder();
        var totale = new Macro(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        if (ingredienti.isEmpty()) mancanti.add("Aggiungi almeno un ingrediente.");

        for (JsonObject ingrediente : ingredienti.getValuesAs(JsonObject.class)) {
            String nome = ingrediente.getString("nome");
            var voce = voci.get(ingrediente.getString("voceCatalogoId", ""));
            var peso = ingrediente.get("quantitaG");
            var riga = Json.createObjectBuilder(ingrediente).remove("rigaId");
            if (voce == null) mancanti.add(nome + ": scegli l’alimento e la preparazione dal catalogo.");
            if (peso == JsonValue.NULL) mancanti.add(nome + ": indica il peso in grammi.");
            if (voce != null) riga.add("catalogo", voce);
            if (voce != null && peso instanceof JsonNumber g) {
                var contributo = Macro.contributo(voce.getJsonObject("per100g"), g.bigDecimalValue());
                totale = totale.somma(contributo);
                riga.add("contributo", contributo.json());
            } else {
                riga.addNull("contributo");
            }
            righe.add(riga);
        }

        var problemi = mancanti.build();
        var risultato = Json.createObjectBuilder().add("righe", righe).add("daCompletare", problemi)
                .add("profilo", profilo).add("versioneRegola", REGOLA);
        if (!problemi.isEmpty()) {
            return risultato.add("stato", "incompleta").addNull("totali").addNull("simulazione").build();
        }
        // Un piatto nuovo parte dai carboidrati confermati. Per un piatto abituale
        // il calcolo usa la versione corrente della memoria, senza modificarla.
        var corrente = memoria == null ? totale.carboidrati() : BigDecimal.valueOf(memoria.correnteG());
        var simulazione = Json.createObjectBuilder()
                .add("scenario", memoria == null ? "prima_occorrenza" : "piatto_abituale")
                .add("memoriaInizialeG", memoria == null ? corrente : BigDecimal.valueOf(memoria.inizialeG()))
                .add("memoriaCorrenteG", corrente).add("varianzaIniziale", VARIANZA_INIZIALE)
                .add("varianzaCorrente", memoria == null ? VARIANZA_INIZIALE : memoria.varianza())
                .add("unitaSimulate", corrente.movePointLeft(1));
        if (memoria != null) simulazione.add("memoriaId", memoria.id()).add("revisioneMemoria", memoria.revisione());
        return risultato.add("stato", "completa").add("totali", totale.json()).add("simulazione", simulazione).build();
    }
}
