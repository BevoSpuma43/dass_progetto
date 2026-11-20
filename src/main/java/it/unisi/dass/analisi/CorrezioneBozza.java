package it.unisi.dass.analisi;

import jakarta.json.*;
import java.util.*;

/**
 * Valida le righe inviate dall'editor e prepara i dati usati da salvataggio, anteprima e
 * registrazione.
 */
public final class CorrezioneBozza {
    /** Impedisce l'istanziazione di questa classe di utilità. */
    private CorrezioneBozza() {}

    /**
     * Valida ID, nomi, pesi e voci di catalogo. Assegna gli ID alle righe nuove e mantiene fonte e
     * provenienza quando la quantità e il contesto risultano invariati.
     */
    public static JsonArray prepara(JsonArray righe, JsonArray precedenti, JsonArray catalogo) {
        if (righe == null || righe.size() > 30) throw invalida();
        var usati = new HashSet<String>();
        var nuove = Json.createArrayBuilder();
        for (var valore : righe) {
            if (!(valore instanceof JsonObject r)) throw invalida();
            String id = r.getString("rigaId", "");
            var vecchia = precedenti.getValuesAs(JsonObject.class).stream()
                    .filter(v -> v.getString("rigaId").equals(id)).findFirst();
            if (!id.isEmpty() && (vecchia.isEmpty() || !usati.add(id))) throw invalida();
            String nome = r.getString("nome", "").strip();
            if (nome.isEmpty() || nome.length() > 500) throw invalida();
            JsonValue riferimento = r.getOrDefault("voceCatalogoId", JsonValue.NULL);
            var voce = catalogo.getValuesAs(JsonObject.class).stream()
                    .filter(v -> v.get("id").equals(riferimento)).findFirst();
            if (riferimento != JsonValue.NULL && voce.isEmpty()) throw invalida();
            JsonValue peso = r.getOrDefault("quantitaG", JsonValue.NULL);
            if (peso != JsonValue.NULL && (!(peso instanceof JsonNumber n) || n.bigDecimalValue().signum() <= 0))
                throw invalida();
            var proposta = vecchia.filter(v -> nome.equals(v.getString("nome")))
                    .map(v -> AbbinamentoCatalogo.suggerisci(v, catalogo));
            boolean manuale = r.getBoolean("sceltaCatalogoManuale", false) ||
                    vecchia.map(v -> v.getBoolean("sceltaCatalogoManuale", false)).orElse(false) ||
                    proposta.map(v -> !riferimento.equals(v.get("voceCatalogoId"))).orElse(false);
            var nota = manuale ? Optional.<JsonObject>empty() : proposta.filter(v -> v.containsKey("notaAbbinamento"));
            String stato = voce.map(v -> v.getString("preparazione")).orElse(r.getString("stato","non_noto"));
            if (nota.isPresent()) stato = nota.get().getString("stato");
            if (!Set.of("cotto","crudo","non_noto").contains(stato)) throw invalida();
            String origine = peso == JsonValue.NULL ? "da_chiarire" : "corretta";
            if (peso instanceof JsonNumber n && vecchia.isPresent()) {
                var prima = vecchia.get();
                if (prima.get("quantitaG") instanceof JsonNumber grammi &&
                        grammi.bigDecimalValue().compareTo(n.bigDecimalValue()) == 0 &&
                        nome.equals(prima.getString("nome")) && stato.equals(prima.getString("stato"))) origine = prima.getString("origineQuantita");
            }
            String abbinamento = voce.isPresent() ? "abbinato" : "da_chiarire";
            if (voce.isEmpty() && vecchia.isPresent() && nome.equals(vecchia.get().getString("nome"))
                    && vecchia.get().isNull("voceCatalogoId")) abbinamento = vecchia.get().getString("statoAbbinamento");
            var aggiornata = Json.createObjectBuilder().add("rigaId",id.isEmpty() ? UUID.randomUUID().toString() : id)
                    .add("nome",nome).add("stato",stato).add("quantitaG",peso).add("unita","g")
                    .add("origineQuantita",origine).add("voceCatalogoId",riferimento)
                    .add("statoAbbinamento",abbinamento);
            if (manuale) aggiornata.add("sceltaCatalogoManuale", true);
            nota.ifPresent(v -> aggiornata.add("notaAbbinamento", v.getString("notaAbbinamento")));
            if (vecchia.isPresent() && !origine.equals("corretta") && nome.equals(vecchia.get().getString("nome"))
                    && stato.equals(vecchia.get().getString("stato")) && origine.equals(vecchia.get().getString("origineQuantita"))) {
                for (String k : List.of("motivazione","fonteUrl","fonteTitolo"))
                    if (vecchia.get().containsKey(k)) aggiornata.add(k,vecchia.get().get(k));
            }
            nuove.add(aggiornata);
        }
        return nuove.build();
    }

    /** Costruisce l'errore HTTP 400 usato quando una riga della bozza non è valida. */
    private static ErroreAnalisi invalida() {
        return new ErroreAnalisi(400,"RIGA_NON_VALIDA","Controlla nome, peso positivo e voce di catalogo per ogni ingrediente.");
    }
}
