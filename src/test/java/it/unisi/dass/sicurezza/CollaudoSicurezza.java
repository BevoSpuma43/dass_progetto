package it.unisi.dass.sicurezza;

import it.unisi.dass.analisi.ErroreAnalisi;
import jakarta.json.Json;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import javax.crypto.KeyAgreement;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.SecretKeySpec;

/** Collaudo offline dello scambio ECDH, della derivazione HKDF e del canale cifrato. */
public class CollaudoSicurezza {

    public static void main(String[] args) throws Exception {
        verificaHkdf();
        verificaScambio();
        verificaPunti();
        verificaScadenza();
        verificaIsolamentoChiavi();
        System.out.println("OK: HKDF (RFC 5869), scambio ECDH P-256, buste del canale, nonce ripetuti, punti fuori curva, scadenza e chiavi distinte.");
    }

    /** Vettore di prova 1 della RFC 5869 per HKDF-SHA-256. */
    private static void verificaHkdf() {
        byte[] ikm = new byte[22];
        Arrays.fill(ikm, (byte) 0x0b);
        byte[] sale = HexFormat.of().parseHex("000102030405060708090a0b0c");
        byte[] info = HexFormat.of().parseHex("f0f1f2f3f4f5f6f7f8f9");
        String atteso = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865";
        verifica(HexFormat.of().formatHex(ScambioChiavi.hkdf(sale, ikm, info, 42)).equals(atteso), "HKDF-SHA-256 conforme alla RFC 5869");
    }

    /** Browser e Payara derivano la stessa chiave senza scambiarsi chiavi private; la busta funziona solo per la sua richiesta. */
    private static void verificaScambio() throws Exception {
        var scambi = new ScambioChiavi();
        var generatore = KeyPairGenerator.getInstance("EC");
        generatore.initialize(new ECGenParameterSpec("secp256r1"));
        var browser = generatore.generateKeyPair();
        byte[] pubblicaBrowser = ScambioChiavi.puntoRaw((ECPublicKey) browser.getPublic());
        verifica(pubblicaBrowser.length == 65 && pubblicaBrowser[0] == 0x04, "punto non compresso di 65 byte");

        var apertura = scambi.apri("utente1", "storico", pubblicaBrowser);
        verifica(apertura.improntaBrowser().equals(ScambioChiavi.impronta(pubblicaBrowser)), "impronta della chiave del browser");
        verifica(apertura.improntaServer().matches("[0-9a-f]{4}( [0-9a-f]{4}){3}"), "formato dell'impronta");
        var json = apertura.json();
        verifica(!json.toString().contains("privat"), "nessuna chiave privata nella risposta");

        // Lato browser: ECDH con la chiave pubblica di Payara, poi HKDF con lo stesso sale e le stesse informazioni.
        var accordo = KeyAgreement.getInstance("ECDH");
        accordo.init(browser.getPrivate());
        accordo.doPhase(ScambioChiavi.importaPunto(apertura.chiavePubblica()), true);
        byte[] chiaveBrowser = ScambioChiavi.hkdf(apertura.sale(), accordo.generateSecret(),
                ScambioChiavi.info(apertura.scambioId(), "utente1", "storico"), 32);
        var sessione = scambi.sessione(apertura.scambioId());
        verifica(Arrays.equals(chiaveBrowser, sessione.chiave.getEncoded()), "stessa chiave condivisa da entrambe le parti");
        var chiave = new SecretKeySpec(chiaveBrowser, "AES");

        var filtro = new CanaleCifratoFilter();
        String percorso = "/dass/api/profili/utente1/documenti";
        String nonce = UUID.randomUUID().toString();
        var richiesta = richiesta(busta(chiave, sessione.id, "GET", percorso + "?tipo=pasto", nonce, System.currentTimeMillis()),
                "GET", percorso, "tipo=pasto", new byte[0]);
        var descrittore = filtro.verificaBusta(sessione, richiesta);
        verifica(descrittore.nonce().equals(nonce), "busta valida accettata");
        errore("BUSTA_RIPETUTA", 409, () -> filtro.verificaBusta(sessione, richiesta));
        errore("BUSTA_NON_CORRISPONDENTE", 400, () -> filtro.verificaBusta(sessione, richiesta(
                busta(chiave, sessione.id, "GET", percorso + "?tipo=foto", UUID.randomUUID().toString(), System.currentTimeMillis()),
                "GET", percorso, "tipo=pasto", new byte[0])));
        errore("BUSTA_NON_CORRISPONDENTE", 400, () -> filtro.verificaBusta(sessione, richiesta(
                busta(chiave, sessione.id, "GET", percorso, UUID.randomUUID().toString(), System.currentTimeMillis()),
                "DELETE", percorso, null, new byte[0])));
        errore("BUSTA_SCADUTA", 400, () -> filtro.verificaBusta(sessione, richiesta(
                busta(chiave, sessione.id, "GET", percorso, UUID.randomUUID().toString(), System.currentTimeMillis() - 600_000),
                "GET", percorso, null, new byte[0])));
        var altraChiave = new SecretKeySpec(new byte[32], "AES");
        errore("BUSTA_NON_VALIDA", 400, () -> filtro.verificaBusta(sessione, richiesta(
                busta(altraChiave, sessione.id, "GET", percorso, UUID.randomUUID().toString(), System.currentTimeMillis()),
                "GET", percorso, null, new byte[0])));
        errore("BUSTA_ASSENTE", 400, () -> filtro.verificaBusta(sessione, richiesta(null, "GET", percorso, null, new byte[0])));

        // Il corpo è legato al nonce della sua richiesta: un corpo copiato da un'altra richiesta non si apre.
        String nonceCorpo = UUID.randomUUID().toString();
        byte[] contenuto = "{\"tipo\":\"pasto\"}".getBytes(StandardCharsets.UTF_8);
        byte[] iv = CanaleCifrato.nuovoIv();
        byte[] cifrato = CanaleCifrato.cifra(chiave, iv, contenuto, CanaleCifrato.aadCorpo(sessione.id, nonceCorpo));
        byte[] corpo = Json.createObjectBuilder().add("iv", Base64.getEncoder().encodeToString(iv))
                .add("dati", Base64.getEncoder().encodeToString(cifrato)).build().toString().getBytes(StandardCharsets.UTF_8);
        var put = new CanaleCifratoFilter.Descrittore("PUT", percorso + "/abc", nonceCorpo, System.currentTimeMillis());
        verifica(Arrays.equals(CanaleCifratoFilter.leggiCorpo(sessione, put, richiesta(null, "PUT", percorso, null, corpo)), contenuto), "corpo decifrato");
        var altroNonce = new CanaleCifratoFilter.Descrittore("PUT", percorso + "/abc", UUID.randomUUID().toString(), System.currentTimeMillis());
        errore("BUSTA_NON_VALIDA", 400, () -> CanaleCifratoFilter.leggiCorpo(sessione, altroNonce, richiesta(null, "PUT", percorso, null, corpo)));

        // Due scambi dello stesso browser producono chiavi diverse.
        var secondo = scambi.apri("utente1", "storico", pubblicaBrowser);
        verifica(!Arrays.equals(scambi.sessione(secondo.scambioId()).chiave.getEncoded(), chiaveBrowser), "chiave diversa per ogni scambio");
    }

    /** Un punto fuori dalla curva o di lunghezza errata viene rifiutato prima dell'accordo. */
    private static void verificaPunti() {
        byte[] falso = new byte[65];
        falso[0] = 0x04;
        falso[64] = 1;
        errore("CHIAVE_PUBBLICA_NON_VALIDA", 400, () -> ScambioChiavi.importaPunto(falso));
        errore("CHIAVE_PUBBLICA_NON_VALIDA", 400, () -> ScambioChiavi.importaPunto(new byte[33]));
        errore("CHIAVE_PUBBLICA_NON_VALIDA", 400, () -> new ScambioChiavi().apri("utente1", "storico", falso));
    }

    /** Dopo cinque minuti lo scambio non è più valido. */
    private static void verificaScadenza() throws Exception {
        var orologio = new OrologioRegolabile(Instant.parse("2026-11-22T10:00:00Z"));
        var scambi = new ScambioChiavi(orologio);
        var generatore = KeyPairGenerator.getInstance("EC");
        generatore.initialize(new ECGenParameterSpec("secp256r1"));
        byte[] pubblica = ScambioChiavi.puntoRaw((ECPublicKey) generatore.generateKeyPair().getPublic());
        String id = scambi.apri("utente2", "salvataggio", pubblica).scambioId();
        verifica(scambi.sessione(id).profiloId().equals("utente2"), "sessione attiva");
        orologio.avanza(ScambioChiavi.DURATA);
        errore("SCAMBIO_SCADUTO", 401, () -> scambi.sessione(id));
        errore("SCAMBIO_ASSENTE", 401, () -> scambi.sessione(id));
        errore("SCAMBIO_ASSENTE", 401, () -> scambi.sessione(null));
        verifica(scambi.attive() == 0, "sessioni scadute rimosse");
    }

    /** Proprietà su cui si basa la dimostrazione: un testo cifrato con una chiave non si apre con un'altra. */
    private static void verificaIsolamentoChiavi() throws Exception {
        var generatore = KeyGenerator.getInstance("AES");
        generatore.init(256);
        var utente1 = generatore.generateKey();
        var utente2 = generatore.generateKey();
        String aad = "DASS|diario|v1|utente1|pasto|p-1";
        byte[] iv = CanaleCifrato.nuovoIv();
        byte[] pasto = CanaleCifrato.cifra(utente1, iv, "{\"nome\":\"Fusilli\"}".getBytes(StandardCharsets.UTF_8), aad);
        verifica(new String(CanaleCifrato.decifra(utente1, iv, pasto, aad), StandardCharsets.UTF_8).contains("Fusilli"), "il proprietario legge il pasto");
        errore("BUSTA_NON_VALIDA", 400, () -> CanaleCifrato.decifra(utente2, iv, pasto, aad));
        errore("BUSTA_NON_VALIDA", 400, () -> CanaleCifrato.decifra(utente1, iv, pasto, aad.replace("utente1", "utente2")));
    }

    /** Busta X-DASS-Busta come la prepara il browser. */
    private static String busta(javax.crypto.SecretKey chiave, String scambio, String metodo, String percorso, String nonce, long istante) {
        byte[] descrittore = Json.createObjectBuilder().add("metodo", metodo).add("percorso", percorso).add("nonce", nonce)
                .add("istante", istante).build().toString().getBytes(StandardCharsets.UTF_8);
        byte[] iv = CanaleCifrato.nuovoIv();
        byte[] cifrato = CanaleCifrato.cifra(chiave, iv, descrittore, CanaleCifrato.aadRichiesta(scambio));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(iv) + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(cifrato);
    }

    /** Richiesta HTTP minima per il filtro, senza contenitore servlet. */
    private static HttpServletRequest richiesta(String busta, String metodo, String percorso, String query, byte[] corpo) {
        return (HttpServletRequest) Proxy.newProxyInstance(CollaudoSicurezza.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class},
                (proxy, m, a) -> switch (m.getName()) {
                    case "getHeader" -> CanaleCifrato.INTESTAZIONE_BUSTA.equals(a[0]) ? busta : null;
                    case "getMethod" -> metodo;
                    case "getRequestURI" -> percorso;
                    case "getQueryString" -> query;
                    case "getContextPath" -> "/dass";
                    case "getInputStream" -> {
                        var input = new ByteArrayInputStream(corpo);
                        yield new ServletInputStream() {
                            @Override public int read() { return input.read(); }
                            @Override public boolean isFinished() { return input.available() == 0; }
                            @Override public boolean isReady() { return true; }
                            @Override public void setReadListener(ReadListener l) {}
                        };
                    }
                    case "toString" -> "richiesta di collaudo";
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private static final class OrologioRegolabile extends Clock {
        private Instant ora;

        OrologioRegolabile(Instant ora) { this.ora = ora; }
        void avanza(Duration durata) { ora = ora.plus(durata); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zona) { return this; }
        @Override public Instant instant() { return ora; }
    }

    @FunctionalInterface
    private interface Prova {
        void esegui() throws Exception;
    }

    private static void errore(String codice, int stato, Prova prova) {
        try {
            prova.esegui();
            throw new AssertionError("Atteso errore " + codice);
        } catch (ErroreAnalisi e) {
            verifica(e.codice().equals(codice) && e.statoHttp() == stato, "atteso " + codice + ", ottenuto " + e.codice());
        } catch (Exception e) {
            throw new AssertionError("Eccezione inattesa per " + codice, e);
        }
    }

    private static void verifica(boolean ok, String cosa) {
        if (!ok) throw new AssertionError("Verifica fallita: " + cosa);
    }
}
