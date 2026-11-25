package it.unisi.dass.analisi;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Catalogo nutrizionale come lo legge Claude: una scheda per riga con un numero breve, nome,
 * preparazione e fonte. Claude risponde con il numero della scheda scelta e il server lo riconduce
 * all'id del catalogo; un numero che non esiste viene scartato.
 */
public final class ElencoCatalogo {
    public static final ElencoCatalogo VUOTO = new ElencoCatalogo("", List.of());
    static final String ISTRUZIONI = """
            Catalogo nutrizionale di DASS, una scheda per riga: numero | nome | preparazione della scheda | fonte.
            Per ogni ingrediente indica in voceCatalogo il numero della scheda che descrive lo stesso alimento.
            - Scegli l'alimento vero e proprio, non un prodotto derivato o un piatto diverso: per una mela non il succo di mela,
              per l'olio non un alimento conservato sott'olio.
            - La preparazione della scheda vale per i grammi: con una scheda cruda o secca indica i grammi prima della cottura,
              con una scheda cotta i grammi cotti, e usa la stessa preparazione in stato. Se l'alimento è servito cotto ma il
              catalogo ha solo la scheda cruda o secca, converti i grammi con una resa tipica e scrivi la conversione in motivazione.
            - Tra varianti equivalenti scegli la più comune in Italia e segnala nei dubbi una scelta incerta.
            - Preferisci le schede CREA e USDA alle schede sintetiche quando descrivono lo stesso alimento.
            - Se nessuna scheda corrisponde all'alimento, usa null: non forzare l'abbinamento.
            """;
    private final String testo;
    private final List<String> id;

    private ElencoCatalogo(String testo, List<String> id) {
        this.testo = testo;
        this.id = id;
    }

    /** Costruisce l'elenco dalle schede di GET /api/catalogo, nello stesso ordine: la numerazione resta stabile. */
    public static ElencoCatalogo da(JsonArray voci) {
        if (voci == null || voci.isEmpty()) return VUOTO;
        var righe = new StringBuilder(ISTRUZIONI).append('\n');
        var id = new ArrayList<String>();
        for (var voce : voci.getValuesAs(JsonObject.class)) {
            id.add(voce.getString("id"));
            righe.append(id.size()).append(" | ").append(voce.getString("nome")).append(" | ")
                    .append(voce.getString("preparazione", "non_noto")).append(" | ").append(fonte(voce.getString("fonte", ""))).append('\n');
        }
        return new ElencoCatalogo(righe.toString(), List.copyOf(id));
    }

    /** Fonte breve: USDA, CREA, sintetica per le schede di prova di DASS, altrimenti il nome dell'ente. */
    private static String fonte(String fonte) {
        if (fonte.startsWith("USDA")) return "USDA";
        if (fonte.startsWith("CREA")) return "CREA";
        if (fonte.startsWith("DASS")) return "sintetica";
        int fine = fonte.indexOf(',');
        return (fine > 0 ? fonte.substring(0, fine) : fonte).strip();
    }

    boolean vuoto() {
        return id.isEmpty();
    }

    /** Testo per il prompt di sistema. */
    String testo() {
        return testo;
    }

    /** Id della scheda con quel numero, oppure null se il numero non è nell'elenco. */
    String id(long numero) {
        return numero >= 1 && numero <= id.size() ? id.get((int) numero - 1) : null;
    }
}
