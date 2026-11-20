package it.unisi.dass.analisi;

import it.unisi.dass.api.ProvaHaikuServlet;
import jakarta.json.JsonObject;
import jakarta.json.Json;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;

/** Solo nel WAR temporaneo di Verifica-AnalisiPiatto. Il trasporto simulato non apre connessioni. */
@WebServlet("/collaudo/analisi")
@MultipartConfig(maxFileSize = FotoPiatto.MAX_BYTES, maxRequestSize = FotoPiatto.MAX_BYTES + 65536L)
public class ServletAnalisiCollaudo extends ProvaHaikuServlet {
    /**
     * Usa un trasporto simulato per esercitare il flusso reale del client con risposte ripetibili,
     * senza chiamate esterne.
     */
    @Override
    protected JsonObject analizzaConHaiku(byte[] foto, String caption) {
        var invii = new java.util.concurrent.atomic.AtomicInteger();
        var proposta = new HaikuClient("test", "haiku-di-collaudo", richiesta -> {
            if (invii.getAndIncrement() == 0) return new HaikuClient.Esito(200, """
                    {"stop_reason":"end_turn","content":[{"type":"text","text":"Per quattro persone: 280 g di pesce, 20 g di olio.",
                    "citations":[{"type":"web_search_result_location","title":"Ricetta di collaudo","url":"https://example.org/ricetta"}]}]}
                    """);
            if (caption.contains("croissant")) return new HaikuClient.Esito(200, """
                    {"model":"haiku-di-collaudo","stop_reason":"tool_use","content":[{"type":"tool_use","name":"proponi_ingredienti",
                    "input":{"ingredienti":[{"nome":"Farina di grano tenero tipo 00","stato":"crudo",
                    "origineQuantita":"stimata","quantitaG":50}],"dubbi":[]}}]}
                    """);
            if (caption.contains("ristorante")) return new HaikuClient.Esito(200, """
                    {"model":"haiku-di-collaudo","stop_reason":"tool_use","content":[{"type":"tool_use","name":"proponi_ingredienti",
                    "input":{"ingredienti":[
                    {"nome":"Triglia","stato":"crudo","origineQuantita":"ricetta","quantitaG":70,"fonteUrl":"https://example.org/ricetta","motivazione":"280 g per quattro porzioni, peso della ricetta a crudo."},
                    {"nome":"Olio di oliva","stato":"crudo","origineQuantita":"ricetta","quantitaG":5,"fonteUrl":"https://example.org/ricetta","motivazione":"20 g per quattro porzioni."},
                    {"nome":"Fusilli","stato":"cotto","origineQuantita":"stimata","quantitaG":180,"motivazione":"Stima visiva della pasta servita."}],
                    "dubbi":[]}}]}
                    """);
            var corpo = """
                    {"model":"haiku-di-collaudo","stop_reason":"tool_use","content":[{
                      "type":"tool_use","name":"proponi_ingredienti","input":{"ingredienti":[
                        {"nome":"fusilli","stato":"crudo","quantitaG":80,"origineQuantita":"caption"},
                        {"nome":"Sugo di pomodoro","stato":"cotto","quantitaG":80,"origineQuantita":"caption"},
                        {"nome":"Triglia","stato":"cotto"},
                        {"nome":"Riso","stato":"crudo","quantitaG":20,"origineQuantita":"stimata"}],
                        "dubbi":["Quanto sugo?"]}}]}
                    """;
            if (caption.isBlank()) corpo = corpo.replace("\"caption\"", "\"stimata\"");
            return new HaikuClient.Esito(200, corpo);
        }).analizza(foto, caption);
        return Json.createObjectBuilder(proposta).add("origineAnalisi","simulata").build();
    }
}
