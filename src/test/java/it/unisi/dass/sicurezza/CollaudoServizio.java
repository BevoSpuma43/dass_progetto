package it.unisi.dass.sicurezza;

import it.unisi.dass.analisi.ErroreAnalisi;
import jakarta.json.*;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.KeyAgreement;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Collaudo di un DASS avviato: lo stesso protocollo del browser (scambio ECDH, buste cifrate, documenti
 * cifrati con una chiave del diario che Payara non riceve) eseguito via HTTP contro il server vero.
 */
public class CollaudoServizio {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static String base;

    public static void main(String[] args) throws Exception {
        base = args.length > 0 ? args[0] : "http://127.0.0.1:8080/dass/";
        var profili = leggiJson(HTTP.send(HttpRequest.newBuilder(URI.create(base + "api/profili")).build(), HttpResponse.BodyHandlers.ofString()).body()).asJsonArray();
        verifica(profili.size() == 2 && profili.toString().contains("\"utente1\"") && profili.toString().contains("\"utente2\""), "profili utente1 e utente2");
        verifica(!profili.toString().contains("paz-demo"), "paz-demo assente");

        var senza = HTTP.send(HttpRequest.newBuilder(URI.create(base + "api/profili/utente1/documenti?tipo=pasto")).build(), HttpResponse.BodyHandlers.ofString());
        verifica(senza.statusCode() == 401 && senza.body().contains("SCAMBIO_ASSENTE"), "documenti negati senza scambio di chiavi");

        // Chiave del diario: resta in questo processo, come nel browser.
        var generatore = KeyGenerator.getInstance("AES");
        generatore.init(256);
        SecretKey diario = generatore.generateKey();
        String id = "collaudo-" + UUID.randomUUID();
        String segreto = "Fusilli di collaudo " + UUID.randomUUID();
        byte[] iv = CanaleCifrato.nuovoIv();
        String aad = "DASS|diario|v1|utente1|pasto|" + id;
        byte[] cifrato = CanaleCifrato.cifra(diario, iv, Json.createObjectBuilder().add("nome", segreto).build().toString().getBytes(StandardCharsets.UTF_8), aad);

        var scrittura = Canale.apri("utente1", "collaudo-salvataggio");
        var salvato = scrittura.richiesta("PUT", "api/profili/utente1/documenti/" + id, Json.createObjectBuilder().add("tipo", "pasto")
                .add("revisione", 0).add("iv", b64(iv)).add("dati", b64(cifrato)).build());
        verifica(salvato.stato == 200 && salvato.json.asJsonObject().getInt("revisione") == 1, "documento cifrato salvato");
        verifica(scrittura.ultimaRispostaCifrata, "risposta cifrata con la chiave dello scambio");

        var lettura = Canale.apri("utente1", "collaudo-storico");
        var elenco = lettura.richiesta("GET", "api/profili/utente1/documenti?tipo=pasto", null);
        verifica(elenco.stato == 200 && elenco.json.toString().contains(id), "elenco dei pasti di utente1");
        verifica(!elenco.json.toString().contains("Fusilli di collaudo"), "nessun contenuto in chiaro arriva da Payara");
        var documento = lettura.richiesta("GET", "api/profili/utente1/documenti/" + id, null).json.asJsonObject();
        byte[] chiaro = CanaleCifrato.decifra(diario, Base64.getDecoder().decode(documento.getString("iv")),
                Base64.getDecoder().decode(documento.getString("dati")), aad);
        verifica(new String(chiaro, StandardCharsets.UTF_8).contains(segreto), "la chiave del diario decifra il pasto");

        var altroProfilo = lettura.richiesta("GET", "api/profili/utente2/documenti?tipo=pasto", null);
        verifica(altroProfilo.stato == 403 && altroProfilo.json.toString().contains("SCAMBIO_DI_ALTRO_PROFILO"), "scambio di utente1 rifiutato sui dati di utente2");
        verifica(lettura.ripeti().stato == 409, "busta ripetuta rifiutata");
        var utente2 = Canale.apri("utente2", "collaudo-storico");
        var negato = utente2.richiesta("GET", "api/profili/utente2/documenti/" + id, null);
        verifica(negato.stato == 404, "utente2 non legge il pasto di utente1");
        verifica(utente2.richiesta("GET", "api/profili/utente2/documenti?tipo=pasto", null).json.asJsonArray().stream()
                .noneMatch(d -> d.asJsonObject().getString("id").equals(id)), "il pasto non compare nel diario di utente2");

        // Vista di H2 dal profilo utente2: il record di utente1 c'è, ma è cifrato e un'altra chiave non lo apre.
        var vista = utente2.richiesta("GET", "api/archivio-h2?limite=100", null).json.asJsonObject();
        var riga = vista.getJsonArray("documenti").stream().map(JsonValue::asJsonObject)
                .filter(d -> d.getString("id").equals(id)).findFirst().orElseThrow(() -> new AssertionError("record assente dalla vista di H2"));
        verifica(riga.getString("profilo").equals("utente1") && !riga.containsKey("dati") && !vista.toString().contains("Fusilli di collaudo"),
                "vista di H2 con il record cifrato di utente1");
        var grezzo = utente2.richiesta("GET", "api/archivio-h2/utente1/" + id, null).json.asJsonObject();
        SecretKey altroDiario = generatore.generateKey();
        boolean aperto;
        try {
            CanaleCifrato.decifra(altroDiario, Base64.getDecoder().decode(grezzo.getString("iv")), Base64.getDecoder().decode(grezzo.getString("dati")), aad);
            aperto = true;
        } catch (ErroreAnalisi e) {
            aperto = false;
        }
        verifica(!aperto, "la chiave di un altro diario non apre il record di utente1");
        verifica(grezzo.getString("dati").equals(documento.getString("dati")), "la vista di H2 restituisce lo stesso testo cifrato");

        var analisi = Canale.apri("utente1", "collaudo-caricamento").richiesta("POST", "api/analisi-piatto",
                Json.createObjectBuilder().add("foto", "").add("caption", "").build());
        verifica(analisi.stato == 400 && analisi.json.toString().contains("CONSENSO_MANCANTE"), "analisi senza consenso rifiutata");

        var rimozione = Canale.apri("utente1", "collaudo-rimozione").richiesta("DELETE", "api/profili/utente1/documenti/" + id + "?revisione=1", null);
        verifica(rimozione.stato == 200 && rimozione.json.asJsonObject().getBoolean("eliminato"), "documento eliminato");
        System.out.println("OK: profili, canale cifrato, documenti cifrati, isolamento tra profili, buste ripetute, consenso e rimozione sul server " + base);
    }

    /** Scambio ECDH di un'azione e richieste nel suo canale cifrato, come js/sicurezza/scambio.js. */
    static final class Canale {
        final String id;
        final SecretKey chiave;
        HttpRequest ultima;
        boolean ultimaRispostaCifrata;

        private Canale(String id, SecretKey chiave) {
            this.id = id;
            this.chiave = chiave;
        }

        static Canale apri(String profilo, String azione) throws Exception {
            var generatore = KeyPairGenerator.getInstance("EC");
            generatore.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair coppia = generatore.generateKeyPair();
            byte[] pubblica = ScambioChiavi.puntoRaw((ECPublicKey) coppia.getPublic());
            var corpo = Json.createObjectBuilder().add("profiloId", profilo).add("azione", azione).add("chiavePubblica", b64(pubblica)).build();
            var risposta = HTTP.send(HttpRequest.newBuilder(URI.create(base + "api/scambi")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(corpo.toString())).build(), HttpResponse.BodyHandlers.ofString());
            verifica(risposta.statusCode() == 201, "scambio aperto (HTTP " + risposta.statusCode() + " " + risposta.body() + ")");
            var apertura = leggiJson(risposta.body()).asJsonObject();
            verifica(apertura.getString("improntaBrowser").equals(ScambioChiavi.impronta(pubblica)), "impronta del browser confermata da Payara");
            var accordo = KeyAgreement.getInstance("ECDH");
            accordo.init(coppia.getPrivate());
            accordo.doPhase(ScambioChiavi.importaPunto(Base64.getDecoder().decode(apertura.getString("chiavePubblica"))), true);
            byte[] materiale = ScambioChiavi.hkdf(Base64.getDecoder().decode(apertura.getString("sale")), accordo.generateSecret(),
                    ScambioChiavi.info(apertura.getString("scambioId"), profilo, azione), 32);
            return new Canale(apertura.getString("scambioId"), new SecretKeySpec(materiale, "AES"));
        }

        record Esito(int stato, JsonValue json) {}

        Esito richiesta(String metodo, String percorso, JsonObject corpo) throws Exception {
            var uri = URI.create(base + percorso);
            String nonce = UUID.randomUUID().toString();
            byte[] descrittore = Json.createObjectBuilder().add("metodo", metodo).add("percorso", uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()))
                    .add("nonce", nonce).add("istante", System.currentTimeMillis()).build().toString().getBytes(StandardCharsets.UTF_8);
            byte[] ivBusta = CanaleCifrato.nuovoIv();
            String busta = Base64.getUrlEncoder().withoutPadding().encodeToString(ivBusta) + "." + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(CanaleCifrato.cifra(chiave, ivBusta, descrittore, CanaleCifrato.aadRichiesta(id)));
            var costruttore = HttpRequest.newBuilder(uri).header(CanaleCifrato.INTESTAZIONE_SCAMBIO, id).header(CanaleCifrato.INTESTAZIONE_BUSTA, busta);
            if (corpo == null) {
                costruttore.method(metodo, HttpRequest.BodyPublishers.noBody());
            } else {
                byte[] ivCorpo = CanaleCifrato.nuovoIv();
                byte[] dati = CanaleCifrato.cifra(chiave, ivCorpo, corpo.toString().getBytes(StandardCharsets.UTF_8), CanaleCifrato.aadCorpo(id, nonce));
                var busta2 = Json.createObjectBuilder().add("iv", b64(ivCorpo)).add("dati", b64(dati)).build();
                costruttore.header("Content-Type", "application/json").method(metodo, HttpRequest.BodyPublishers.ofString(busta2.toString()));
            }
            ultima = costruttore.build();
            return invia(ultima, nonce);
        }

        /** Rispedisce identica l'ultima richiesta, come farebbe chi l'ha intercettata. */
        Esito ripeti() throws Exception {
            return invia(ultima, null);
        }

        private Esito invia(HttpRequest richiesta, String nonce) throws Exception {
            var risposta = HTTP.send(richiesta, HttpResponse.BodyHandlers.ofString());
            ultimaRispostaCifrata = "1".equals(risposta.headers().firstValue(CanaleCifrato.INTESTAZIONE_CIFRATO).orElse(""));
            if (!ultimaRispostaCifrata) return new Esito(risposta.statusCode(), leggiJson(risposta.body()));
            var busta = leggiJson(risposta.body()).asJsonObject();
            byte[] chiaro = CanaleCifrato.decifra(chiave, Base64.getDecoder().decode(busta.getString("iv")),
                    Base64.getDecoder().decode(busta.getString("dati")), CanaleCifrato.aadRisposta(id, nonce));
            String testo = new String(chiaro, StandardCharsets.UTF_8);
            return new Esito(risposta.statusCode(), testo.isEmpty() ? JsonValue.NULL : leggiJson(testo));
        }
    }

    private static JsonValue leggiJson(String testo) {
        try (var reader = Json.createReader(new StringReader(testo))) {
            return reader.readValue();
        }
    }

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    private static void verifica(boolean ok, String cosa) {
        if (!ok) throw new AssertionError("Verifica fallita: " + cosa);
    }
}
