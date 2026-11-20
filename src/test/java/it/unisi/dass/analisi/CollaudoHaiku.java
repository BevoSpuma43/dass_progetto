package it.unisi.dass.analisi;

import jakarta.json.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Collaudo offline del client Haiku e della normalizzazione delle foto. */
public class CollaudoHaiku {
    private static final String CAPTION = "Pasta al pomodoro, 80 g di pasta a crudo";
    private static final String MODELLO = "haiku-di-prova";
    private static final String PROPOSTA = """
            {"model":"haiku-di-prova","stop_reason":"tool_use","content":[
              {"type":"tool_use","name":"proponi_ingredienti","input":{"ingredienti":[
                {"nome":"pasta","quantitaG":80,"stato":"crudo","origineQuantita":"caption"},
                {"nome":"sugo","quantitaG":null,"stato":"cotto","origineQuantita":"da_chiarire"}],
                "dubbi":["Quanto sugo?"]}}]}
            """;

    /**
     * Esegue i casi di prova per JPEG, payload, ricerca, citazioni, risposta strutturata ed errori del
     * fornitore.
     */
    public static void main(String[] args) throws Exception {
        var sorgente = new BufferedImage(1400, 700, BufferedImage.TYPE_INT_RGB);
        var buffer = new ByteArrayOutputStream();
        ImageIO.write(sorgente, "png", buffer);
        buffer.write("EXIF-FITTIZIO".getBytes(StandardCharsets.US_ASCII));
        byte[] jpeg = FotoPiatto.prepara(new ByteArrayInputStream(buffer.toByteArray()));
        var foto = ImageIO.read(new ByteArrayInputStream(jpeg));
        verifica(foto.getWidth() == 1280 && foto.getHeight() == 640);
        verifica(!new String(jpeg, StandardCharsets.ISO_8859_1).contains("EXIF-FITTIZIO"));
        errore("FOTO_ILLEGGIBILE", 415, () -> FotoPiatto.prepara(new ByteArrayInputStream(new byte[]{0,1,2})));

        var client = new HaikuClient("segreto-fittizio", MODELLO, req -> {
            verifica(req.uri().toString().equals("https://api.anthropic.com/v1/messages"));
            verifica(req.method().equals("POST") && req.headers().firstValue("x-api-key").orElseThrow().equals("segreto-fittizio"));
            return new HaikuClient.Esito(200, PROPOSTA);
        });
        var richiesta = client.payload(jpeg, CAPTION);
        var contenuto = richiesta.getJsonArray("messages").getJsonObject(0).getJsonArray("content");
        verifica(contenuto.size() == 2 && contenuto.getJsonObject(1).getString("text").equals(CAPTION));
        verifica(richiesta.getString("model").equals(MODELLO) && !richiesta.toString().contains("segreto-fittizio"));
        var source = contenuto.getJsonObject(0).getJsonObject("source");
        verifica(source.getString("media_type").equals("image/jpeg"));
        verifica(source.getString("data").equals(Base64.getEncoder().encodeToString(jpeg)));
        verifica(client.payload(jpeg, "").getJsonArray("messages").getJsonObject(0).getJsonArray("content").size() == 1);
        var esito = client.analizza(jpeg, CAPTION);
        var ingredienti = esito.getJsonArray("ingredienti");
        verifica(ingredienti.getJsonObject(0).getInt("quantitaG") == 80);
        verifica(ingredienti.getJsonObject(1).isNull("quantitaG"));
        verifica(esito.getString("modello").equals(MODELLO));
        var secondoInvio = new java.util.concurrent.atomic.AtomicInteger();
        var daRicetta = PROPOSTA.replace("\"origineQuantita\":\"caption\"",
                "\"origineQuantita\":\"ricetta\",\"fonteUrl\":\"https://example.org/primo\",\"motivazione\":\"320 g per quattro persone.\"");
        var conRicerca = new HaikuClient("test", MODELLO, req -> new HaikuClient.Esito(200,
                switch (secondoInvio.getAndIncrement()) {
                    case 0 -> "{\"stop_reason\":\"pause_turn\",\"content\":[]}";
                    case 1 -> """
                            {"stop_reason":"end_turn","content":[{"type":"text","text":"Pasta, 320 g per quattro.",
                            "citations":[{"type":"web_search_result_location","title":"Primo","url":"https://example.org/primo"}]}]}
                            """;
                    default -> daRicetta;
                })).analizza(jpeg, CAPTION);
        verifica(secondoInvio.get() == 3 && conRicerca.getJsonArray("ingredienti").getJsonObject(0)
                .getString("fonteTitolo").equals("Primo"));
        errore("RISPOSTA_NON_VALIDA",502,() -> InterpreteProposta.interpreta(daRicetta,true));
        var tentativi = new java.util.concurrent.atomic.AtomicInteger();
        var senzaWeb = new HaikuClient("test",MODELLO,req -> tentativi.getAndIncrement() == 0 ?
                new HaikuClient.Esito(400,"") : new HaikuClient.Esito(200,PROPOSTA)).analizza(jpeg,CAPTION);
        verifica(senzaWeb.getJsonArray("dubbi").toString().contains("senza fonti"));
        var stimata = InterpreteProposta.interpreta(PROPOSTA.replace("\"caption\"", "\"stimata\""), false);
        verifica(stimata.getJsonArray("ingredienti").getJsonObject(0).getString("origineQuantita").equals("stimata"));
        errore("CHIAVE_ASSENTE", 503, () -> new HaikuClient("", MODELLO, req -> {
            throw new AssertionError("Una richiesta senza chiave ha raggiunto il trasporto");
        }).analizza(jpeg, CAPTION));
        errore("TIMEOUT", 504, () -> new HaikuClient("finta", MODELLO, req -> {
            throw new HttpTimeoutException("test");
        }).analizza(jpeg, CAPTION));
        errore("RETE", 503, () -> new HaikuClient("finta", MODELLO, req -> {
            throw new IOException("test");
        }).analizza(jpeg, CAPTION));
        for (String incompleta : new String[]{"non-json", "{}", PROPOSTA.replace("tool_use","max_tokens"),
                PROPOSTA.replace(":80",":-2"), PROPOSTA.replace("proponi_ingredienti","altro_tool"),
                PROPOSTA.replace(":null",":0"), PROPOSTA.replace("\"crudo\"", "null")}) {
            errore("RISPOSTA_NON_VALIDA", 502, () -> InterpreteProposta.interpreta(incompleta, true));
        }
        errore("RISPOSTA_NON_VALIDA", 502, () -> InterpreteProposta.interpreta(PROPOSTA, false));
        verificaErroreHttp(jpeg, 401, 502, "ACCESSO_NEGATO");
        verificaErroreHttp(jpeg, 403, 502, "ACCESSO_NEGATO");
        verificaErroreHttp(jpeg, 404, 502, "MODELLO_ASSENTE");
        verificaErroreHttp(jpeg, 429, 429, "LIMITE_API");
        verificaErroreHttp(jpeg, 400, 502, "RICHIESTA_RIFIUTATA");
        verificaErroreHttp(jpeg, 529, 503, "FORNITORE_INDISPONIBILE");
        System.out.println("OK: immagine, payload, proposta con e senza caption, chiave assente, timeout, rete, risposta malformata ed errori HTTP.");
    }

    /** Simula uno stato HTTP del fornitore e verifica lo stato e il codice restituiti da DASS. */
    private static void verificaErroreHttp(byte[] jpeg, int fornitore, int stato, String codice) {
        errore(codice, stato, () -> new HaikuClient("finta", MODELLO,
                req -> new HaikuClient.Esito(fornitore, "corpo riservato")).analizza(jpeg, CAPTION));
    }

    /**
     * Esegue una prova che deve fallire, controllando codice, stato e assenza del corpo riservato nel
     * messaggio.
     */
    private static void errore(String codice, int stato, Runnable prova) {
        try {
            prova.run();
            throw new AssertionError("Atteso errore " + codice);
        } catch (ErroreAnalisi e) {
            verifica(e.codice().equals(codice) && e.statoHttp() == stato);
            verifica(!e.getMessage().contains("corpo riservato"));
        }
    }

    /** Interrompe il collaudo quando una condizione attesa è falsa. */
    private static void verifica(boolean ok) {
        if (!ok) throw new AssertionError("Verifica della prova Haiku fallita");
    }
}
