package it.unisi.dass.analisi;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Ricerca nel catalogo per nome e preparazione. L'abbinamento delle righe di un pasto avviene nel
 * browser (js/logica/abbinamento.js), perché le bozze sono cifrate con la chiave del profilo.
 */
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
    private static final Pattern RUOLO = Pattern.compile(
            "\\s*\\((?:per\\s+(?:l['’])?)?(?:impasto|sfogliatura|lamelle|superficie|guarnizione|spennellatura|finitura|decorazione)\\b[^()]*\\)",
            Pattern.CASE_INSENSITIVE);

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
}
