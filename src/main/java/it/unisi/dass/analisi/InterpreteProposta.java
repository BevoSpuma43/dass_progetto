package it.unisi.dass.analisi;

import jakarta.json.*;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Converte la risposta di Anthropic nel formato interno della proposta. Una riga incoerente non
 * invalida tutta la proposta: viene corretta come farebbe l'utente nell'editor. Le regole sulle fonti
 * restano rigide: un URL che la ricerca non ha citato non viene mai mostrato.
 */
public final class InterpreteProposta {
    private static final Logger LOG = Logger.getLogger(InterpreteProposta.class.getName());
    private static final Set<String> STATI = Set.of("crudo", "cotto", "non_noto");
    private static final Set<String> ORIGINI = Set.of("caption", "stimata", "ricetta", "da_chiarire");
    private static final Pattern PESO_TESTUALE = Pattern.compile("\\s*(\\d+(?:[.,]\\d+)?)\\s*(?:g)?\\s*", Pattern.CASE_INSENSITIVE);
    private static final int MASSIMO_RIGHE = 30;

    /** Impedisce l'istanziazione di questa classe di utilità. */
    private InterpreteProposta() {}

    /** Interpreta una risposta senza fonti di ricette ammesse, ad esempio nei collaudi. */
    public static JsonObject interpreta(String risposta, boolean conCaption) {
        return interpreta(risposta, conCaption, Map.of(), "sconosciuto");
    }

    /**
     * Estrae la proposta dall'output strutturato (o dal vecchio strumento proponi_ingredienti) e
     * normalizza righe, grammi, provenienza e citazioni. Segnala i dati che l'utente deve verificare.
     */
    static JsonObject interpreta(String risposta, boolean conCaption, Map<String, String> fonti, String modelloRichiesto) {
        JsonObject envelope;
        try (var reader = Json.createReader(new StringReader(risposta))) {
            envelope = reader.readObject();
        } catch (RuntimeException e) {
            throw scartata("risposta non in formato JSON");
        }
        String stop = envelope.get("stop_reason") instanceof JsonString s ? s.getString() : "";
        if (stop.equals("refusal"))
            throw new ErroreAnalisi(502, "RICHIESTA_RIFIUTATA", "Claude non ha analizzato questa foto. Prova con un’altra immagine o compila la bozza a mano.");
        if (stop.equals("max_tokens")) throw scartata("proposta interrotta perché troppo lunga");
        if (!stop.equals("end_turn") && !stop.equals("tool_use")) throw scartata("stop_reason " + (stop.isEmpty() ? "assente" : stop));
        JsonObject proposta = estrai(envelope);

        var correzioni = new ArrayList<String>();
        var ingredienti = Json.createArrayBuilder();
        int righe = 0;
        boolean senzaNome = false;
        boolean fontiNonCitate = false;
        var lista = proposta.getJsonArray("ingredienti");
        for (int indice = 0; indice < Math.min(lista.size(), MASSIMO_RIGHE); indice++) {
            String nome = lista.get(indice) instanceof JsonObject o ? testo(o.get("nome")) : null;
            if (nome == null) {
                senzaNome = true;
                correzioni.add("riga " + (indice + 1) + ": nome assente");
                continue;
            }
            var i = lista.getJsonObject(indice);
            String stato = valoreTra(i.get("stato"), STATI, "non_noto");
            String origine = valoreTra(i.get("origineQuantita"), ORIGINI, "da_chiarire");
            BigDecimal grammi = peso(i.get("quantitaG"));
            if (grammi == null && i.get("quantitaG") != null && i.get("quantitaG") != JsonValue.NULL && !origine.equals("da_chiarire"))
                correzioni.add(nome + ": peso non valido " + i.get("quantitaG"));
            // Un peso assente o non valido, o dichiarato da chiarire, resta da chiarire nell'editor.
            if (grammi == null || origine.equals("da_chiarire")) {
                grammi = null;
                origine = "da_chiarire";
            }
            if (origine.equals("caption") && !conCaption) {
                origine = "stimata";
                correzioni.add(nome + ": peso attribuito a una descrizione assente");
            }
            String url = null;
            if (origine.equals("ricetta")) {
                url = fonteCitata(fonti, i.get("fonteUrl"));
                if (url == null) {
                    origine = "stimata";
                    fontiNonCitate = true;
                    correzioni.add(nome + ": ricetta non citata dalla ricerca " + i.get("fonteUrl"));
                }
            }
            var riga = Json.createObjectBuilder().add("nome", nome).add("stato", stato);
            if (grammi == null) riga.addNull("quantitaG");
            else riga.add("quantitaG", grammi);
            riga.add("origineQuantita", origine);
            if (url != null) riga.add("fonteUrl", url).add("fonteTitolo", fonti.get(url));
            String motivazione = testo(i.get("motivazione"));
            if (motivazione != null) riga.add("motivazione", motivazione);
            ingredienti.add(riga);
            righe++;
        }

        var domande = Json.createArrayBuilder();
        int dubbi = 0;
        if (proposta.get("dubbi") instanceof JsonArray elenco) {
            for (var valore : elenco) {
                String dubbio = testo(valore);
                if (dubbio != null && dubbi < MASSIMO_RIGHE) {
                    domande.add(dubbio);
                    dubbi++;
                }
            }
        }
        if (lista.size() > MASSIMO_RIGHE) domande.add("Claude ha proposto più di 30 ingredienti: sono mostrati i primi 30.");
        if (senzaNome) domande.add("Un ingrediente non ha un nome riconoscibile. Verifica la foto e aggiungilo manualmente.");
        if (fontiNonCitate)
            domande.add("Per alcune righe Claude ha indicato una ricetta che la ricerca non ha restituito: quei pesi sono trattati come stime da verificare.");
        if (fonti.isEmpty() && righe > 0)
            domande.add("Ricerca senza fonti utilizzabili. Le stime si basano sulla foto o su porzioni tipiche.");
        var dubbiFinali = domande.build();
        if (righe == 0 && dubbiFinali.isEmpty())
            dubbiFinali = Json.createArrayBuilder().add("Claude non ha riconosciuto ingredienti nella foto. Puoi compilare la bozza a mano.").build();
        if (!correzioni.isEmpty()) LOG.info("Proposta di Claude corretta prima dell'editor: " + correzioni);
        String modello = testo(envelope.get("model"));
        return Json.createObjectBuilder().add("modello", modello == null ? modelloRichiesto : modello)
                .add("statoAnalisi", "da_confermare").add("ingredienti", ingredienti).add("dubbi", dubbiFinali).build();
    }

    /**
     * Con l'output strutturato la proposta è il JSON del testo della risposta. Per compatibilità accetta
     * anche un unico blocco tool_use dello strumento proponi_ingredienti.
     */
    private static JsonObject estrai(JsonObject envelope) {
        if (!(envelope.get("content") instanceof JsonArray contenuto)) throw scartata("contenuto assente");
        var strumenti = contenuto.stream().filter(v -> v instanceof JsonObject o && "tool_use".equals(o.getString("type", ""))).toList();
        JsonObject proposta;
        if (!strumenti.isEmpty()) {
            var blocco = strumenti.getFirst().asJsonObject();
            if (strumenti.size() != 1 || !"proponi_ingredienti".equals(blocco.getString("name", "")))
                throw scartata("strumento assente o ripetuto");
            proposta = blocco.get("input") instanceof JsonObject o ? o : null;
        } else {
            var testo = new StringBuilder();
            for (var valore : contenuto) {
                if (valore instanceof JsonObject o && "text".equals(o.getString("type", "")) && o.get("text") instanceof JsonString s)
                    testo.append(s.getString());
            }
            if (testo.isEmpty()) throw scartata("proposta assente");
            try (var reader = Json.createReader(new StringReader(testo.toString()))) {
                proposta = reader.readObject();
            } catch (RuntimeException e) {
                throw scartata("JSON della proposta illeggibile");
            }
        }
        if (proposta == null || !(proposta.get("ingredienti") instanceof JsonArray)) throw scartata("elenco degli ingredienti assente");
        return proposta;
    }

    /** Valore testuale tra quelli ammessi, altrimenti il predefinito. */
    private static String valoreTra(JsonValue valore, Set<String> ammessi, String predefinito) {
        return valore instanceof JsonString s && ammessi.contains(s.getString()) ? s.getString() : predefinito;
    }

    /** Testo senza spazi ai bordi, accorciato a 500 caratteri; null se assente o vuoto. */
    private static String testo(JsonValue valore) {
        if (!(valore instanceof JsonString s) || s.getString().isBlank()) return null;
        String pulito = s.getString().strip();
        return pulito.length() > 500 ? pulito.substring(0, 499).stripTrailing() + "…" : pulito;
    }

    /** Grammi positivi: accetta anche un numero scritto come testo, ad esempio "80" o "12,5 g". */
    private static BigDecimal peso(JsonValue valore) {
        BigDecimal grammi = null;
        if (valore instanceof JsonNumber n) grammi = n.bigDecimalValue();
        else if (valore instanceof JsonString s) {
            var m = PESO_TESTUALE.matcher(s.getString());
            if (m.matches()) grammi = new BigDecimal(m.group(1).replace(',', '.'));
        }
        return grammi != null && grammi.signum() > 0 ? grammi : null;
    }

    /** URL citato dalla ricerca che corrisponde a quello indicato, ignorando barra finale, frammento e maiuscole. */
    private static String fonteCitata(Map<String, String> fonti, JsonValue valore) {
        if (!(valore instanceof JsonString s) || s.getString().isBlank()) return null;
        String url = s.getString().strip();
        if (fonti.containsKey(url)) return url;
        String cercata = forma(url);
        return fonti.keySet().stream().filter(f -> forma(f).equals(cercata)).findFirst().orElse(null);
    }

    private static String forma(String url) {
        return url.strip().replaceFirst("#.*$", "").replaceFirst("/+$", "").toLowerCase(Locale.ROOT);
    }

    /** Errore HTTP 502 per una proposta esterna inutilizzabile, con il motivo nel messaggio. */
    private static ErroreAnalisi scartata(String motivo) {
        LOG.warning("Risposta di Claude scartata: " + motivo);
        return new ErroreAnalisi(502, "RISPOSTA_NON_VALIDA", "Claude ha restituito una proposta incompleta o illeggibile (" + motivo + "). Riprova.");
    }
}
