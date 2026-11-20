package it.unisi.dass.analisi;

import jakarta.json.*;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Cerca corrispondenze nel catalogo, tenendo conto della preparazione a cui si riferiscono i grammi. */
public final class AbbinamentoCatalogo {
    private static final Map<String,String> SINONIMI = Map.ofEntries(
            Map.entry("pasta di semola","pasta"),
            Map.entry("fusilli","pasta"), Map.entry("pasta tipo fusilli","pasta"),
            Map.entry("spaghetti","pasta"), Map.entry("penne","pasta"),
            Map.entry("salsa di pomodoro","sugo di pomodoro"),
            Map.entry("olio d oliva","olio di oliva"), Map.entry("olio extravergine di oliva","olio di oliva"),
            Map.entry("olio extravergine d oliva","olio di oliva"), Map.entry("olio evo","olio di oliva"),
            Map.entry("farina tipo 00","farina 00"), Map.entry("farina di tipo 00","farina 00"),
            Map.entry("farina di grano tenero tipo 00","farina 00"), Map.entry("farina di grano tenero 00","farina 00"),
            Map.entry("flour 00","farina 00"),
            Map.entry("farina tipo 0","farina 0"), Map.entry("farina di tipo 0","farina 0"),
            Map.entry("farina di grano tenero tipo 0","farina 0"), Map.entry("farina di frumento tipo 0","farina 0"),
            Map.entry("farina 0 forte","farina 0"), Map.entry("farina forte tipo 0","farina 0"),
            Map.entry("farina di grano tenero tipo 0 forte","farina 0"), Map.entry("farina tipo 0 forte","farina 0"),
            Map.entry("latte","latte intero"), Map.entry("latte vaccino","latte intero"),
            Map.entry("latte di mucca","latte intero"), Map.entry("latte intero vaccino","latte intero"),
            Map.entry("latte intero 3 25 di grassi con vitamina d aggiunta","latte intero"),
            Map.entry("uovo","uovo intero"), Map.entry("uova","uovo intero"), Map.entry("uova intere","uovo intero"),
            Map.entry("uovo intero grande grado a","uovo intero"),
            Map.entry("burro classico","burro"), Map.entry("burro non salato","burro"),
            Map.entry("zucchero di canna","zucchero di canna grezzo"),
            Map.entry("zucchero di canna chiaro","zucchero di canna grezzo"),
            Map.entry("zucchero grezzo di canna","zucchero di canna grezzo"),
            Map.entry("zucchero semolato bianco","zucchero semolato"), Map.entry("zucchero bianco","zucchero semolato"),
            Map.entry("lievito di birra secco","lievito di birra secco attivo"),
            Map.entry("sale","sale da cucina"), Map.entry("sale fino","sale da cucina"));
    private static final Set<String> INGREDIENTI_DA_IMPASTO = Set.of("farina 0", "farina 00", "latte intero", "burro",
            "uovo intero", "zucchero di canna grezzo", "zucchero semolato", "lievito di birra secco attivo", "sale da cucina");
    private static final Pattern RUOLO = Pattern.compile(
            "\\s*\\((?:per\\s+(?:l['’])?)?(?:impasto|sfogliatura|lamelle|superficie|guarnizione|spennellatura|finitura|decorazione)\\b[^()]*\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PESO = Pattern.compile("(?<![\\p{L}\\d.,-])(\\d+(?:[.,]\\d+)?)\\s*(kg|grammi|grammo|gr|g)\\b");

    /** Impedisce l'istanziazione di questa classe di utilità. */
    private AbbinamentoCatalogo() {}

    /**
     * Rende confrontabili i nomi convertendoli in minuscolo, togliendo gli accenti e riducendo la
     * punteggiatura a spazi.
     */
    public static String normalizza(String testo) {
        return Normalizer.normalize(testo.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    /**
     * Rimuove dal nome i ruoli come «guarnizione» e il suffisso crudo o cotto: la preparazione è
     * confrontata a parte.
     */
    private static String nomeSenzaPreparazione(String nome) {
        return normalizza(RUOLO.matcher(nome).replaceAll(" ")).replaceFirst(" (?:a |da )?(?:crud[oaie]|cott[oaie])$", "");
    }

    /** Ricava il nome canonico dell'ingrediente, applicando i sinonimi riconosciuti dal catalogo. */
    private static String alimento(String nome) {
        String base = nomeSenzaPreparazione(nome);
        return SINONIMI.getOrDefault(base, base);
    }

    /**
     * Verifica se nome e preparazione soddisfano il filtro. I numeri devono coincidere per intero,
     * così farina 0 e farina 00 restano distinte.
     */
    public static boolean corrispondeAllaRicerca(String nome, String preparazione, String filtro) {
        String cerca = normalizza(filtro);
        String testo = " " + normalizza(nome + " " + preparazione) + " ";
        if (alimento(filtro).equals(alimento(nome))) return true;
        return Arrays.stream(cerca.split(" ")).allMatch(parola -> parola.matches("\\d+")
                ? testo.contains(" " + parola + " ") : testo.contains(parola));
    }

    /**
     * Conserva gli abbinamenti esistenti e le scelte manuali. Cerca una voce compatibile per nome e
     * preparazione e la propone solo se è univoca. Segnala le possibili approssimazioni.
     */
    public static JsonObject suggerisci(JsonObject riga, JsonArray catalogo) {
        if (riga.getBoolean("sceltaCatalogoManuale", false) || riga.getString("voceCatalogoId", null) != null) return riga;
        String originale = riga.getString("nome");
        String nome = alimento(originale);
        String stato = riga.getString("stato", "non_noto");
        var esito = Json.createObjectBuilder(riga).remove("notaAbbinamento");
        var voci = catalogo.getValuesAs(JsonObject.class);
        var candidati = voci.stream().filter(v -> alimento(v.getString("nome")).equals(nome)).toList();
        if (candidati.isEmpty() && !nome.isBlank()) {
            candidati = voci.stream().filter(v -> alimento(v.getString("nome")).startsWith(nome + " ")).toList();
        }
        var compatibili = candidati.stream().filter(v -> v.getString("preparazione").equals(stato)).toList();
        String nota = notaVariante(originale);
        if (compatibili.isEmpty() && INGREDIENTI_DA_IMPASTO.contains(nome) &&
                (stato.equals("non_noto") || riga.getString("origineQuantita").equals("ricetta"))) {
            compatibili = candidati.stream().filter(v -> v.getString("preparazione").equals("crudo")).toList();
            if (!compatibili.isEmpty()) {
                nota += "La scheda si riferisce all’ingrediente prima della cottura. Verifica che i grammi si riferiscano alla dose della ricetta.";
            }
        }
        if (compatibili.size() != 1) {
            return esito.addNull("voceCatalogoId").add("statoAbbinamento",
                    candidati.isEmpty() ? "sconosciuto" : "da_chiarire").build();
        }
        var voce = compatibili.getFirst();
        if (nota.isEmpty() && !alimento(voce.getString("nome")).equals(nome)) {
            nota = "Verifica la variante proposta dal catalogo.";
        }
        if (!nota.isEmpty()) esito.add("notaAbbinamento", nota.strip());
        return esito.add("voceCatalogoId", voce.getString("id")).add("statoAbbinamento", "abbinato").build();
    }

    /**
     * Spiega le assunzioni fatte quando il nome indica una variante più specifica o più generica della
     * scheda disponibile.
     */
    private static String notaVariante(String originale) {
        return switch (nomeSenzaPreparazione(originale)) {
            case "latte", "latte vaccino", "latte di mucca" -> "È stato proposto latte intero. Verifica la variante. ";
            case "farina 0 forte", "farina forte tipo 0", "farina tipo 0 forte", "farina di grano tenero tipo 0 forte" ->
                    "La scheda è della farina tipo 0 generica. La forza della farina può variare. ";
            case "zucchero di canna chiaro" -> "È stato proposto zucchero di canna grezzo. Verifica la variante. ";
            default -> "";
        };
    }

    /**
     * Assegna un ID a ogni riga proposta, cerca la scheda del catalogo e lascia da chiarire i pesi che
     * non si possono attribuire alla descrizione.
     */
    public static JsonObject collega(JsonObject proposta, JsonArray catalogo, String caption) {
        var righe = proposta.getJsonArray("ingredienti").getValuesAs(JsonObject.class);
        var risultato = Json.createArrayBuilder();
        for (var riga : righe) {
            var r = Json.createObjectBuilder(suggerisci(riga, catalogo)).add("unita","g").add("rigaId",UUID.randomUUID().toString());
            if (riga.getString("origineQuantita").equals("caption") &&
                    !pesoAttribuibile(caption, riga, righe)) {
                r.addNull("quantitaG").add("origineQuantita","da_chiarire");
                r.add("motivazione","Il peso dichiarato non è attribuibile con certezza a questo ingrediente.");
            }
            risultato.add(r);
        }
        return Json.createObjectBuilder(proposta).add("origineAnalisi",proposta.getString("origineAnalisi","haiku"))
                .add("ingredienti",risultato).build();
    }

    // Un numero presente nella descrizione, da solo, non dimostra a quale alimento appartenga.
    /**
     * Controlla che il peso dichiarato compaia accanto al nome o a un sinonimo di un solo ingrediente
     * e corrisponda alla quantità proposta.
     */
    private static boolean pesoAttribuibile(String caption, JsonObject riga, List<JsonObject> righe) {
        String nome = alimento(riga.getString("nome"));
        for (String frase : caption.toLowerCase(Locale.ROOT).split("[;\\n]|,(?!\\d)|\\b(?:e|con)\\b")) {
            var peso = PESO.matcher(frase);
            if (!peso.find()) continue;
            var grammi = new BigDecimal(peso.group(1).replace(',', '.'));
            if (peso.group(2).equals("kg")) grammi = grammi.movePointRight(3);
            String prima = normalizza(frase.substring(0,peso.start()));
            String dopo = normalizza(frase.substring(peso.end())).replaceFirst("^di ", "");
            if (peso.find() || grammi.compareTo(riga.getJsonNumber("quantitaG").bigDecimalValue()) != 0) continue;
            String parole = " " + normalizza(frase) + " ";
            var citate = righe.stream().filter(i -> {
                String base = alimento(i.getString("nome"));
                return parole.contains(" " + base + " ") || parole.contains(" " + normalizza(i.getString("nome")) + " ")
                        || SINONIMI.entrySet().stream().anyMatch(s -> s.getValue().equals(base)
                                && parole.contains(" " + s.getKey() + " "));
            }).toList();
            if (citate.size() != 1 || !alimento(citate.getFirst().getString("nome")).equals(nome)) continue;
            var nomi = new HashSet<String>();
            nomi.add(nome);
            nomi.add(normalizza(riga.getString("nome")));
            SINONIMI.forEach((alias, base) -> { if (base.equals(nome)) nomi.add(alias); });
            String primaDelPeso = prima.replaceFirst(" (?:a |da )?(?:crud[oaie]|cott[oaie])$", "");
            for (String forma : nomi) {
                if ((" " + primaDelPeso).endsWith(" " + forma) || (dopo + " ").startsWith(forma + " ")) return true;
            }
        }
        return false;
    }
}
