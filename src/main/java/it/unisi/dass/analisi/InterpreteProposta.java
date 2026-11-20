package it.unisi.dass.analisi;

import jakarta.json.*;
import java.io.StringReader;
import java.util.Set;
import java.util.Map;

/** Converte la risposta di Anthropic nel formato interno della proposta e ne controlla i campi. */
public final class InterpreteProposta {
    private static final Set<String> STATI = Set.of("crudo", "cotto", "non_noto");
    private static final Set<String> ORIGINI = Set.of("caption", "stimata", "ricetta", "da_chiarire");

    /** Impedisce l'istanziazione di questa classe di utilità. */
    private InterpreteProposta() {}

    /** Interpreta una risposta senza fonti di ricette ammesse, ad esempio nei collaudi. */
    public static JsonObject interpreta(String risposta, boolean conCaption) {
        return interpreta(risposta, conCaption, Map.of());
    }

    /**
     * Estrae il singolo risultato dello strumento e valida righe, grammi, provenienza e citazioni.
     * Segnala i dati che l'utente deve verificare.
     */
    static JsonObject interpreta(String risposta, boolean conCaption, Map<String,String> fonti) {
        try (var reader = Json.createReader(new StringReader(risposta))) {
            JsonObject envelope = reader.readObject();
            if (!"tool_use".equals(envelope.getString("stop_reason"))) throw rispostaInvalida();
            JsonObject proposta = null;
            for (JsonValue valore : envelope.getJsonArray("content")) {
                var blocco = valore.asJsonObject();
                if (!"tool_use".equals(blocco.getString("type"))) continue;
                if (proposta != null || !"proponi_ingredienti".equals(blocco.getString("name"))) throw rispostaInvalida();
                proposta = blocco.getJsonObject("input");
            }
            if (proposta == null) throw rispostaInvalida();
            var lista = proposta.getJsonArray("ingredienti");
            var dubbi = proposta.containsKey("dubbi") ? proposta.getJsonArray("dubbi") : JsonValue.EMPTY_JSON_ARRAY;
            if (lista.size() > 30 || dubbi.size() > 30 || (lista.isEmpty() && dubbi.isEmpty())) throw rispostaInvalida();
            var ingredienti = Json.createArrayBuilder();
            boolean senzaNome = false;
            for (JsonValue valore : lista) {
                var i = valore.asJsonObject();
                if (!i.containsKey("nome") || i.isNull("nome") || i.getString("nome").isBlank()) {
                    senzaNome = true;
                    continue;
                }
                String nome = testo(i.getString("nome"));
                String stato = i.containsKey("stato") ? i.getString("stato") : "non_noto";
                String origine = i.getString("origineQuantita", "da_chiarire");
                JsonValue grammi = i.getOrDefault("quantitaG", JsonValue.NULL);
                // Un campo omesso resta correggibile. Un numero senza provenienza resta da chiarire.
                if (grammi == JsonValue.NULL || !i.containsKey("origineQuantita")) {
                    grammi = JsonValue.NULL;
                    origine = "da_chiarire";
                }
                if (!STATI.contains(stato) || !ORIGINI.contains(origine) || (!conCaption && origine.equals("caption"))) throw rispostaInvalida();
                if (origine.equals("da_chiarire")) {
                    if (grammi != JsonValue.NULL) throw rispostaInvalida();
                } else if (!(grammi instanceof JsonNumber n) || n.bigDecimalValue().signum() <= 0) {
                    throw rispostaInvalida();
                }
                var riga = Json.createObjectBuilder().add("nome", nome).add("stato", stato)
                        .add("quantitaG", grammi).add("origineQuantita", origine);
                if (origine.equals("ricetta")) {
                    String url = i.getString("fonteUrl","");
                    if (!fonti.containsKey(url)) throw rispostaInvalida();
                    riga.add("fonteUrl", url).add("fonteTitolo", fonti.get(url));
                }
                if (i.containsKey("motivazione")) riga.add("motivazione",testo(i.getString("motivazione")));
                ingredienti.add(riga);
            }
            var domande = Json.createArrayBuilder();
            for (JsonValue v : dubbi) domande.add(testo(((JsonString) v).getString()));
            if (senzaNome) domande.add("Un ingrediente non ha un nome riconoscibile. Verifica la foto e aggiungilo manualmente.");
            if (fonti.isEmpty() && !lista.isEmpty()) domande.add("Ricerca senza fonti utilizzabili. Le stime si basano sulla foto o su porzioni tipiche.");
            return Json.createObjectBuilder().add("modello", testo(envelope.getString("model")))
                    .add("statoAnalisi", "da_confermare").add("ingredienti", ingredienti).add("dubbi", domande).build();
        } catch (RuntimeException e) {
            throw rispostaInvalida();
        }
    }

    /** Rimuove gli spazi ai bordi e rifiuta un campo testuale vuoto o più lungo di 500 caratteri. */
    private static String testo(String s) {
        if (s.isBlank() || s.length() > 500) throw rispostaInvalida();
        return s.strip();
    }

    /** Restituisce l'errore HTTP 502 per una proposta esterna inutilizzabile. */
    private static ErroreAnalisi rispostaInvalida() {
        return new ErroreAnalisi(502, "RISPOSTA_NON_VALIDA", "Haiku ha restituito una proposta incompleta o illeggibile. Riprova.");
    }
}
