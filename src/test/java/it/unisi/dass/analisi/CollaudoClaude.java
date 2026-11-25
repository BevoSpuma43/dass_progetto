package it.unisi.dass.analisi;

import jakarta.json.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

/** Collaudo offline del client Claude (Sonnet 5.5), dell'interprete della proposta e delle foto. */
public class CollaudoClaude {
    private static final String CAPTION = "Pasta al pomodoro, 80 g di pasta a crudo";
    private static final String FONTE = "https://example.org/fusilli";
    private static final String RICERCA = """
            {"stop_reason":"end_turn","content":[{"type":"text","text":"Fusilli 320 g per quattro persone.",
            "citations":[{"type":"web_search_result_location","title":"Fusilli","url":"https://example.org/fusilli"}]}]}
            """;

    /** Esegue i casi di prova per foto, richieste, ricerca, output strutturato, correzioni ed errori. */
    public static void main(String[] args) throws Exception {
        byte[] jpeg = verificaFoto();
        verificaRichieste(jpeg);
        verificaCorrezioni(jpeg);
        verificaErrori(jpeg);
        verificaDiagnostica();
        verificaCatalogo(jpeg);
        System.out.println("OK: foto, richieste per Sonnet 5.5, ricerca, output strutturato, correzioni delle righe, errori e diagnostica.");
    }

    /** Claude riceve il catalogo numerato e sceglie la scheda; il server tiene solo numeri esistenti. */
    private static void verificaCatalogo(byte[] jpeg) {
        JsonArray voci;
        try (var reader = Json.createReader(new StringReader("""
                [{"id":"pasta-cotta-v1","nome":"Pasta di semola","preparazione":"cotto","fonte":"CREA, Pasta di semola cotta"},
                 {"id":"pasta-v1","nome":"Pasta","preparazione":"crudo","fonte":"DASS, valori sintetici per 100 g"},
                 {"id":"usda-fdc-1-2026-10-29","nome":"Spaghetti secchi","preparazione":"non_noto","fonte":"USDA FoodData Central"}]
                """))) {
            voci = reader.readArray();
        }
        var elenco = ElencoCatalogo.da(voci);
        verifica(elenco.testo().contains("1 | Pasta di semola | cotto | CREA\n2 | Pasta | crudo | sintetica\n3 | Spaghetti secchi | non_noto | USDA"),
                "elenco numerato con preparazione e fonte");
        verifica(elenco.id(3).equals("usda-fdc-1-2026-10-29") && elenco.id(0) == null && elenco.id(4) == null, "numeri ricondotti agli id");
        verifica(ElencoCatalogo.da(Json.createArrayBuilder().build()).vuoto(), "catalogo vuoto");

        var client = new ClaudeClient("k", null, req -> { throw new AssertionError("nessun invio"); });
        var sistema = client.payload(jpeg, CAPTION, elenco).getJsonArray("system");
        verifica(sistema.size() == 2 && sistema.getJsonObject(0).getString("text").contains("Proponi in italiano"), "istruzioni nel primo blocco");
        verifica(sistema.getJsonObject(1).getJsonObject("cache_control").getString("type").equals("ephemeral")
                && sistema.getJsonObject(1).getString("text").contains("2 | Pasta | crudo | sintetica"), "catalogo in cache nel secondo blocco");
        verifica(client.payload(jpeg, CAPTION).get("system") instanceof JsonString, "senza catalogo il sistema resta un testo");

        var invii = new ArrayList<JsonObject>();
        var esito = new ClaudeClient("k", null, req -> {
            invii.add(corpo(req));
            return new ClaudeClient.Esito(200, invii.size() == 1 ? RICERCA : strutturata("""
                    {"ingredienti":[
                     {"nome":"Fusilli","quantitaG":80,"stato":"crudo","origineQuantita":"stimata","motivazione":"Porzione tipica.","fonteUrl":"","voceCatalogo":3,"schedaApprossimata":false,"trascurabile":false},
                     {"nome":"Sugo","quantitaG":90,"stato":"cotto","origineQuantita":"stimata","motivazione":"Stima.","fonteUrl":"","voceCatalogo":99,"schedaApprossimata":true,"trascurabile":false},
                     {"nome":"Basilico","quantitaG":1,"stato":"crudo","origineQuantita":"stimata","motivazione":"Foglie.","fonteUrl":"","voceCatalogo":null,"schedaApprossimata":false,"trascurabile":true},
                     {"nome":"Gnocchi di zucca","quantitaG":150,"stato":"cotto","origineQuantita":"stimata","motivazione":"Scheda più simile.","fonteUrl":"","voceCatalogo":1,"schedaApprossimata":true,"trascurabile":false},
                     {"nome":"Peperoncino fresco","quantitaG":5,"stato":"crudo","origineQuantita":"stimata","motivazione":"Guarnizione.","fonteUrl":"","voceCatalogo":null,"schedaApprossimata":false,"trascurabile":false}],
                     "dubbi":[]}
                    """));
        }).analizza(jpeg, CAPTION, elenco);
        verifica(invii.get(0).get("system") instanceof JsonString, "la ricerca non riceve il catalogo");
        verifica(invii.get(1).getJsonArray("system").size() == 2, "la proposta riceve il catalogo");
        var righe = esito.getJsonArray("ingredienti");
        verifica(righe.size() == 4 && !righe.toString().contains("Basilico"), "riga trascurabile esclusa");
        verifica(righe.getJsonObject(0).getString("voceCatalogoId").equals("usda-fdc-1-2026-10-29")
                && !righe.getJsonObject(0).getBoolean("schedaApprossimata"), "scheda scelta da Claude");
        verifica(righe.getJsonObject(1).isNull("voceCatalogoId") && !righe.getJsonObject(1).getBoolean("schedaApprossimata"),
                "numero inesistente scartato");
        verifica(righe.getJsonObject(2).getString("voceCatalogoId").equals("pasta-cotta-v1")
                && righe.getJsonObject(2).getBoolean("schedaApprossimata"), "scheda più simile segnalata");
        verifica(righe.getJsonObject(3).isNull("voceCatalogoId"), "nessuna scheda adatta");

        var soloErbe = InterpreteProposta.interpreta(strutturata("""
                {"ingredienti":[{"nome":"Sale","quantitaG":1,"stato":"non_noto","origineQuantita":"stimata","motivazione":"Pizzico.","fonteUrl":"","voceCatalogo":null,"schedaApprossimata":false,"trascurabile":true}],
                 "dubbi":[]}
                """), false);
        verifica(soloErbe.getJsonArray("ingredienti").isEmpty()
                && soloErbe.getJsonArray("dubbi").getString(0).startsWith("Claude ha riconosciuto solo erbe"), "proposta con sole righe trascurabili");
    }

    /** Il log riassume ricerche, errori dello strumento e fonti, e riporta il motivo di un rifiuto di Anthropic. */
    private static void verificaDiagnostica() {
        var ricerca = new RicercaRicette();
        try (var reader = Json.createReader(new StringReader("""
                [{"type":"server_tool_use","id":"s1","name":"web_search","input":{"query":"fusilli al pomodoro"}},
                 {"type":"web_search_tool_result","tool_use_id":"s1","content":{"type":"web_search_tool_result_error","error_code":"max_uses_exceeded"}},
                 {"type":"text","text":"Fusilli 320 g per quattro persone.",
                  "citations":[{"type":"web_search_result_location","title":"Fusilli","url":"https://example.org/fusilli"}]}]
                """))) {
            ricerca.raccogli(reader.readArray());
        }
        verifica(ricerca.riepilogo().equals("ricerche eseguite 1, risultati 0, errori dello strumento [max_uses_exceeded], fonti citate 1"),
                "riepilogo della ricerca: " + ricerca.riepilogo());
        var conRisultati = new RicercaRicette();
        try (var reader = Json.createReader(new StringReader("""
                [{"type":"web_search_tool_result","tool_use_id":"s2","content":[
                  {"type":"web_search_result","url":"https://example.org/a","title":"A"},
                  {"type":"web_search_result","url":"https://example.org/b","title":"B"}]}]
                """))) {
            conRisultati.raccogli(reader.readArray());
        }
        verifica(conRisultati.riepilogo().equals("ricerche eseguite 0, risultati 2, errori dello strumento [], fonti citate 0"),
                "risultati contati senza diventare fonti: " + conRisultati.riepilogo());
        verifica(ClaudeClient.dettaglioErrore("{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"web search non abilitato\"}}")
                .equals(": invalid_request_error · web search non abilitato"), "dettaglio dell'errore di Anthropic");
        verifica(ClaudeClient.dettaglioErrore("<html>").isEmpty() && ClaudeClient.dettaglioErrore(null).isEmpty(), "corpo senza errore JSON");
    }

    /** La foto viene ricodificata in JPEG senza metadati e ridotta a 1280 pixel. */
    private static byte[] verificaFoto() throws IOException {
        var sorgente = new BufferedImage(1400, 700, BufferedImage.TYPE_INT_RGB);
        var buffer = new ByteArrayOutputStream();
        ImageIO.write(sorgente, "png", buffer);
        buffer.write("EXIF-FITTIZIO".getBytes(StandardCharsets.US_ASCII));
        byte[] jpeg = FotoPiatto.prepara(new ByteArrayInputStream(buffer.toByteArray()));
        var foto = ImageIO.read(new ByteArrayInputStream(jpeg));
        verifica(foto.getWidth() == 1280 && foto.getHeight() == 640, "lato massimo 1280");
        verifica(!new String(jpeg, StandardCharsets.ISO_8859_1).contains("EXIF-FITTIZIO"), "metadati rimossi");
        errore("FOTO_ILLEGGIBILE", 415, () -> FotoPiatto.prepara(new ByteArrayInputStream(new byte[]{0, 1, 2})));
        return jpeg;
    }

    /** Forma delle due richieste a Sonnet 5.5: ricerca con web_search e proposta vincolata allo schema. */
    private static void verificaRichieste(byte[] jpeg) {
        var client = new ClaudeClient("segreto-fittizio", null, req -> {
            throw new AssertionError("payload() non deve inviare richieste");
        });
        verifica(client.modello().equals("claude-sonnet-5-5"), "modello predefinito Sonnet 5.5");
        var richiesta = client.payload(jpeg, CAPTION);
        verifica(richiesta.getString("model").equals("claude-sonnet-5-5"), "modello nella richiesta");
        verifica(!richiesta.containsKey("tool_choice") && !richiesta.containsKey("tools"), "nessuno strumento forzato");
        var config = richiesta.getJsonObject("output_config");
        verifica(config.getString("effort").equals("medium"), "effort della proposta");
        verifica(config.getJsonObject("format").getString("type").equals("json_schema"), "output strutturato");
        verifica(!config.getJsonObject("format").toString().contains("maxLength"), "schema senza vincoli non supportati");
        var obbligatori = config.getJsonObject("format").getJsonObject("schema").getJsonObject("properties").getJsonObject("ingredienti")
                .getJsonObject("items").getJsonArray("required").getValuesAs(JsonString::getString);
        verifica(obbligatori.containsAll(List.of("voceCatalogo", "schedaApprossimata", "trascurabile")), "scheda, approssimazione e righe trascurabili nello schema");
        verifica(richiesta.getString("fallbacks").equals("default"), "fallback lato server");
        verifica(richiesta.getInt("max_tokens") >= 16000, "token sufficienti per pensiero e risposta");
        verifica(!richiesta.toString().contains("segreto-fittizio"), "la chiave non entra nel corpo");
        var contenuto = richiesta.getJsonArray("messages").getJsonObject(0).getJsonArray("content");
        verifica(contenuto.size() == 2 && contenuto.getJsonObject(1).getString("text").equals(CAPTION), "foto e descrizione");
        verifica(client.payload(jpeg, " ").getJsonArray("messages").getJsonObject(0).getJsonArray("content").size() == 1, "senza descrizione");

        var invii = new ArrayList<JsonObject>();
        var intestazioni = new ArrayList<String>();
        var esito = new ClaudeClient("segreto-fittizio", null, req -> {
            verifica(req.uri().toString().equals("https://api.anthropic.com/v1/messages"), "endpoint Messages");
            verifica(req.headers().firstValue("x-api-key").orElseThrow().equals("segreto-fittizio"), "chiave nell'intestazione");
            intestazioni.add(req.headers().firstValue("anthropic-beta").orElse(""));
            invii.add(corpo(req));
            return new ClaudeClient.Esito(200, switch (invii.size()) {
                case 1 -> "{\"stop_reason\":\"pause_turn\",\"content\":[{\"type\":\"server_tool_use\",\"id\":\"s1\",\"name\":\"web_search\",\"input\":{}}]}";
                case 2 -> RICERCA;
                default -> strutturata("""
                        {"ingredienti":[{"nome":"Fusilli","quantitaG":80,"stato":"crudo","origineQuantita":"ricetta",
                        "motivazione":"320 g per quattro persone.","fonteUrl":"https://example.org/fusilli"},
                        {"nome":"Sugo di pomodoro","quantitaG":null,"stato":"cotto","origineQuantita":"da_chiarire",
                        "motivazione":"Quantità non visibile.","fonteUrl":""}],"dubbi":["Quanto sugo?"]}
                        """);
            });
        }).analizza(jpeg, CAPTION);
        verifica(invii.size() == 3, "pausa della ricerca ripresa");
        var ricerca = invii.get(0);
        var strumento = ricerca.getJsonArray("tools").getJsonObject(0);
        verifica(strumento.getString("type").equals("web_search_20260209"), "web search recente per Sonnet 5.5");
        verifica(strumento.getJsonArray("allowed_callers").toString().equals("[\"direct\"]"),
                "ricerca diretta: il filtro dinamico toglierebbe le citazioni");
        verifica(ricerca.getJsonObject("output_config").getString("effort").equals("low"), "effort basso per la ricerca");
        verifica(!ricerca.getJsonObject("output_config").containsKey("format"), "ricerca senza output strutturato");
        verifica(invii.get(1).getJsonArray("messages").size() == 2, "turno in pausa ripreso con la risposta");
        verifica(invii.get(2).getJsonObject("output_config").containsKey("format"), "proposta strutturata");
        verifica(intestazioni.stream().allMatch(ClaudeClient.BETA_FALLBACK::equals), "intestazione beta del fallback");
        verifica(invii.get(2).getJsonArray("messages").getJsonObject(0).getJsonArray("content").toString().contains(FONTE), "appunti con la fonte");
        var fusilli = esito.getJsonArray("ingredienti").getJsonObject(0);
        verifica(fusilli.getString("fonteTitolo").equals("Fusilli") && fusilli.getInt("quantitaG") == 80, "riga da ricetta citata");
        verifica(esito.getJsonArray("ingredienti").getJsonObject(1).isNull("quantitaG"), "peso da chiarire");
        verifica(esito.getString("modello").equals("claude-sonnet-5-5"), "modello restituito");

        // Con un modello Haiku 4.x non si inviano effort né fallback e la ricerca usa la versione di base.
        var haiku = new ArrayList<JsonObject>();
        new ClaudeClient("k", "claude-haiku-4-5-20251001", req -> {
            haiku.add(corpo(req));
            verifica(req.headers().firstValue("anthropic-beta").isEmpty(), "niente beta per Haiku 4.5");
            return new ClaudeClient.Esito(200, haiku.size() == 1 ? RICERCA : strutturata("{\"ingredienti\":[],\"dubbi\":[\"Non è cibo.\"]}"));
        }).analizza(jpeg, "");
        verifica(haiku.get(0).getJsonArray("tools").getJsonObject(0).getString("type").equals("web_search_20250305"), "web search di base per Haiku");
        verifica(!haiku.get(1).toString().contains("effort") && !haiku.get(1).containsKey("fallbacks"), "opzioni solo per i modelli recenti");
    }

    /** Righe imperfette corrette una per una, senza scartare l'intera analisi. */
    private static void verificaCorrezioni(byte[] jpeg) {
        String riga = "{\"nome\":\"%s\",\"quantitaG\":%s,\"stato\":\"%s\",\"origineQuantita\":\"%s\",\"motivazione\":\"%s\",\"fonteUrl\":\"%s\"}";
        String proposta = "{\"ingredienti\":[" + String.join(",",
                riga.formatted("Fusilli", "80", "crudo", "ricetta", "Dose della ricetta.", FONTE + "/"),
                riga.formatted("Pomodoro", "120", "crudo", "ricetta", "Dose tipica.", "https://inventata.example/ricetta"),
                riga.formatted("Olio", "6", "crudo", "stimata", "x".repeat(600), ""),
                riga.formatted("Sale", "0", "crudo", "stimata", "Quanto basta.", ""),
                riga.formatted("Basilico", "2", "crudo", "caption", "Dalla descrizione.", ""),
                riga.formatted("Parmigiano", "10", "crudo", "da_chiarire", "Forse.", ""),
                riga.formatted("Pepe", "\"12,5 g\"", "macinato", "stimata", "Una presa.", ""),
                riga.formatted(" ", "5", "crudo", "stimata", "Senza nome.", "")) + "],\"dubbi\":[\"\",\"Il formaggio non si vede.\"]}";
        var invii = new AtomicInteger();
        var esito = new ClaudeClient("k", null, req -> new ClaudeClient.Esito(200, invii.getAndIncrement() == 0 ? RICERCA : strutturata(proposta)))
                .analizza(jpeg, "");
        var righe = new HashMap<String, JsonObject>();
        esito.getJsonArray("ingredienti").getValuesAs(JsonObject.class).forEach(r -> righe.put(r.getString("nome"), r));
        verifica(righe.size() == 7, "riga senza nome scartata, le altre tenute");
        verifica(righe.get("Fusilli").getString("fonteUrl").equals(FONTE), "URL ricondotto alla fonte citata");
        verifica(righe.get("Pomodoro").getString("origineQuantita").equals("stimata") && !righe.get("Pomodoro").containsKey("fonteUrl"), "ricetta non citata");
        verifica(righe.get("Olio").getString("motivazione").length() == 500, "motivazione accorciata");
        verifica(righe.get("Sale").isNull("quantitaG") && righe.get("Sale").getString("origineQuantita").equals("da_chiarire"), "peso nullo da chiarire");
        verifica(righe.get("Basilico").getString("origineQuantita").equals("stimata"), "caption senza descrizione");
        verifica(righe.get("Parmigiano").isNull("quantitaG"), "da chiarire senza peso");
        verifica(righe.get("Pepe").getJsonNumber("quantitaG").doubleValue() == 12.5 && righe.get("Pepe").getString("stato").equals("non_noto"), "peso testuale e stato");
        var dubbi = esito.getJsonArray("dubbi").toString();
        verifica(dubbi.contains("Il formaggio non si vede.") && dubbi.contains("nome riconoscibile") && dubbi.contains("non ha restituito"), "dubbi aggiunti");

        var molte = new StringBuilder("{\"ingredienti\":[");
        for (int i = 0; i < 31; i++) molte.append(i == 0 ? "" : ",").append(riga.formatted("Voce " + i, "10", "crudo", "stimata", "Stima.", ""));
        var lunga = InterpreteProposta.interpreta(strutturata(molte + "],\"dubbi\":[]}"), false);
        verifica(lunga.getJsonArray("ingredienti").size() == 30 && lunga.getJsonArray("dubbi").toString().contains("più di 30"), "massimo 30 righe");
        var vuota = InterpreteProposta.interpreta(strutturata("{\"ingredienti\":[],\"dubbi\":[]}"), false);
        verifica(vuota.getJsonArray("dubbi").getString(0).startsWith("Claude non ha riconosciuto"), "nessun ingrediente");
        var strumento = InterpreteProposta.interpreta("""
                {"model":"m","stop_reason":"tool_use","content":[{"type":"tool_use","name":"proponi_ingredienti",
                "input":{"ingredienti":[{"nome":"Riso","quantitaG":70,"stato":"crudo","origineQuantita":"stimata"}],"dubbi":[]}}]}
                """, false);
        verifica(strumento.getJsonArray("ingredienti").getJsonObject(0).getInt("quantitaG") == 70, "vecchio formato a strumento accettato");
    }

    /** Errori del fornitore, risposte inutilizzabili e condizioni di rete. */
    private static void verificaErrori(byte[] jpeg) {
        for (String inutilizzabile : new String[]{"non-json", "{}", strutturata("non è json"),
                strutturata("{\"dubbi\":[]}"), "{\"stop_reason\":\"end_turn\",\"content\":[]}",
                "{\"stop_reason\":\"tool_use\",\"content\":[{\"type\":\"tool_use\",\"name\":\"altro\",\"input\":{}}]}"}) {
            errore("RISPOSTA_NON_VALIDA", 502, () -> InterpreteProposta.interpreta(inutilizzabile, true));
        }
        try {
            InterpreteProposta.interpreta(strutturata("{}").replace("end_turn", "max_tokens"), true);
            throw new AssertionError("Attesa risposta troncata");
        } catch (ErroreAnalisi e) {
            verifica(e.getMessage().contains("troppo lunga"), "motivo della risposta troncata");
        }
        errore("RICHIESTA_RIFIUTATA", 502, () -> InterpreteProposta.interpreta(
                "{\"stop_reason\":\"refusal\",\"stop_details\":{\"type\":\"refusal\",\"category\":\"general_harms\"},\"content\":[]}", true));
        var tentativi = new AtomicInteger();
        var senzaWeb = new ClaudeClient("k", null, req -> tentativi.getAndIncrement() == 0 ? new ClaudeClient.Esito(400, "")
                : new ClaudeClient.Esito(200, strutturata("{\"ingredienti\":[{\"nome\":\"Riso\",\"quantitaG\":70,\"stato\":\"crudo\",\"origineQuantita\":\"stimata\",\"motivazione\":\"Porzione.\",\"fonteUrl\":\"\"}],\"dubbi\":[]}")))
                .analizza(jpeg, CAPTION);
        verifica(senzaWeb.getJsonArray("dubbi").toString().contains("senza fonti"), "ricerca rifiutata ma proposta riuscita");
        errore("CHIAVE_ASSENTE", 503, () -> new ClaudeClient("", null, req -> {
            throw new AssertionError("Una richiesta senza chiave ha raggiunto il trasporto");
        }).analizza(jpeg, CAPTION));
        errore("TIMEOUT", 504, () -> new ClaudeClient("k", null, req -> { throw new HttpTimeoutException("test"); }).analizza(jpeg, CAPTION));
        errore("RETE", 503, () -> new ClaudeClient("k", null, req -> { throw new IOException("test"); }).analizza(jpeg, CAPTION));
        verificaErroreHttp(jpeg, 401, 502, "ACCESSO_NEGATO");
        verificaErroreHttp(jpeg, 403, 502, "ACCESSO_NEGATO");
        verificaErroreHttp(jpeg, 404, 502, "MODELLO_ASSENTE");
        verificaErroreHttp(jpeg, 429, 429, "LIMITE_API");
        verificaErroreHttp(jpeg, 400, 502, "RICHIESTA_RIFIUTATA");
        verificaErroreHttp(jpeg, 529, 503, "FORNITORE_INDISPONIBILE");
    }

    /** Risposta strutturata come la restituisce Sonnet 5.5: un blocco di pensiero vuoto e il JSON nel testo. */
    private static String strutturata(String json) {
        return Json.createObjectBuilder().add("model", "claude-sonnet-5-5").add("stop_reason", "end_turn")
                .add("content", Json.createArrayBuilder()
                        .add(Json.createObjectBuilder().add("type", "thinking").add("thinking", "").add("signature", "firma"))
                        .add(Json.createObjectBuilder().add("type", "text").add("text", json))).build().toString();
    }

    /** Legge il corpo JSON di una richiesta preparata dal client. */
    private static JsonObject corpo(HttpRequest richiesta) {
        var raccolto = new ByteArrayOutputStream();
        var fine = new java.util.concurrent.CompletableFuture<Void>();
        richiesta.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer b) { byte[] parte = new byte[b.remaining()]; b.get(parte); raccolto.writeBytes(parte); }
            public void onError(Throwable t) { fine.completeExceptionally(t); }
            public void onComplete() { fine.complete(null); }
        });
        fine.join();
        try (var reader = Json.createReader(new StringReader(raccolto.toString(StandardCharsets.UTF_8)))) {
            return reader.readObject();
        }
    }

    /** Simula uno stato HTTP del fornitore e verifica lo stato e il codice restituiti da DASS. */
    private static void verificaErroreHttp(byte[] jpeg, int fornitore, int stato, String codice) {
        errore(codice, stato, () -> new ClaudeClient("k", null, req -> new ClaudeClient.Esito(fornitore, "corpo riservato")).analizza(jpeg, CAPTION));
    }

    /** Esegue una prova che deve fallire, controllando codice, stato e assenza del corpo riservato. */
    private static void errore(String codice, int stato, Prova prova) {
        try {
            prova.esegui();
            throw new AssertionError("Atteso errore " + codice);
        } catch (ErroreAnalisi e) {
            verifica(e.codice().equals(codice) && e.statoHttp() == stato, "codice " + codice + " invece di " + e.codice());
            verifica(!e.getMessage().contains("corpo riservato"), "corpo del fornitore non esposto");
        } catch (Exception e) {
            throw new AssertionError("Eccezione inattesa per " + codice, e);
        }
    }

    @FunctionalInterface
    private interface Prova {
        void esegui() throws Exception;
    }

    /** Interrompe il collaudo quando una condizione attesa è falsa. */
    private static void verifica(boolean ok, String cosa) {
        if (!ok) throw new AssertionError("Verifica fallita: " + cosa);
    }
}
