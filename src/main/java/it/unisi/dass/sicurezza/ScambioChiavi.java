package it.unisi.dass.sicurezza;

import it.unisi.dass.analisi.ErroreAnalisi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Scambio di chiavi ECDH per ogni azione del diario. Browser e Payara generano una coppia P-256
 * effimera, si inviano le chiavi pubbliche e ciascuno usa la propria chiave privata in locale per
 * derivare lo stesso segreto; HKDF-SHA-256 lo trasforma nella chiave AES-GCM che cifra tutte le
 * richieste di quell'azione. La chiave privata di Payara esiste solo dentro {@link #apri}.
 */
@ApplicationScoped
public class ScambioChiavi {
    public static final Duration DURATA = Duration.ofMinutes(5);
    public static final int MASSIMO_RICHIESTE = 500;
    public static final String ALGORITMO = "ECDH P-256 · HKDF-SHA-256 · AES-GCM 256";
    private static final ECParameterSpec P256 = parametriP256();
    private final Map<String, Sessione> sessioni = new ConcurrentHashMap<>();
    private final SecureRandom casuale = new SecureRandom();
    private final Clock orologio;

    /** Sessione di un'azione: la chiave derivata e i nonce già usati, per rifiutare le ripetizioni. */
    public static final class Sessione {
        final String id;
        final String profiloId;
        final String azione;
        final SecretKey chiave;
        final Instant scadenza;
        private final Set<String> nonce = ConcurrentHashMap.newKeySet();
        private final AtomicInteger richieste = new AtomicInteger();

        Sessione(String id, String profiloId, String azione, SecretKey chiave, Instant scadenza) {
            this.id = id;
            this.profiloId = profiloId;
            this.azione = azione;
            this.chiave = chiave;
            this.scadenza = scadenza;
        }

        public String id() { return id; }
        public String profiloId() { return profiloId; }
        public String azione() { return azione; }

        /** Registra il nonce di una richiesta: false se era già stato usato in questo scambio. */
        boolean registraNonce(String valore) {
            return nonce.add(valore);
        }

        boolean superaLimite() {
            return richieste.incrementAndGet() > MASSIMO_RICHIESTE;
        }
    }

    /** Esito dell'apertura, inviato al browser: nessun dato segreto, solo la chiave pubblica di Payara e il sale. */
    public record Apertura(String scambioId, byte[] chiavePubblica, byte[] sale, Instant scadenza,
                           String improntaBrowser, String improntaServer) {
        public JsonObject json() {
            return Json.createObjectBuilder().add("scambioId", scambioId)
                    .add("chiavePubblica", Base64.getEncoder().encodeToString(chiavePubblica))
                    .add("sale", Base64.getEncoder().encodeToString(sale)).add("scadeAlle", scadenza.toString())
                    .add("algoritmo", ALGORITMO).add("improntaBrowser", improntaBrowser).add("improntaServer", improntaServer).build();
        }
    }

    public ScambioChiavi() {
        this(Clock.systemUTC());
    }

    ScambioChiavi(Clock orologio) {
        this.orologio = orologio;
    }

    /**
     * Completa lo scambio a partire dalla chiave pubblica del browser (punto P-256 non compresso, 65
     * byte) e registra la sessione dell'azione.
     */
    public Apertura apri(String profiloId, String azione, byte[] chiavePubblicaBrowser) {
        pulisci();
        var pubblicaBrowser = importaPunto(chiavePubblicaBrowser);
        try {
            var generatore = KeyPairGenerator.getInstance("EC");
            generatore.initialize(new ECGenParameterSpec("secp256r1"), casuale);
            KeyPair coppia = generatore.generateKeyPair();
            var accordo = KeyAgreement.getInstance("ECDH");
            accordo.init(coppia.getPrivate());
            accordo.doPhase(pubblicaBrowser, true);
            byte[] segreto = accordo.generateSecret();
            String id = UUID.randomUUID().toString();
            byte[] sale = new byte[32];
            casuale.nextBytes(sale);
            byte[] materiale = hkdf(sale, segreto, info(id, profiloId, azione), 32);
            Arrays.fill(segreto, (byte) 0);
            var scadenza = orologio.instant().plus(DURATA);
            sessioni.put(id, new Sessione(id, profiloId, azione, new SecretKeySpec(materiale, "AES"), scadenza));
            Arrays.fill(materiale, (byte) 0);
            byte[] pubblicaServer = puntoRaw((ECPublicKey) coppia.getPublic());
            return new Apertura(id, pubblicaServer, sale, scadenza, impronta(chiavePubblicaBrowser), impronta(pubblicaServer));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("ECDH P-256 non disponibile nella JVM.", e);
        }
    }

    /** Sessione attiva dell'azione; HTTP 401 se l'identificativo è assente, sconosciuto o scaduto. */
    public Sessione sessione(String id) {
        var sessione = id == null ? null : sessioni.get(id);
        if (sessione == null)
            throw new ErroreAnalisi(401, "SCAMBIO_ASSENTE", "Questa azione richiede uno scambio di chiavi valido. Riprova.");
        if (!orologio.instant().isBefore(sessione.scadenza)) {
            sessioni.remove(id);
            throw new ErroreAnalisi(401, "SCAMBIO_SCADUTO", "Lo scambio di chiavi è scaduto. Riprova l’azione.");
        }
        return sessione;
    }

    /** Numero di sessioni attive, per i collaudi. */
    int attive() {
        pulisci();
        return sessioni.size();
    }

    private void pulisci() {
        var ora = orologio.instant();
        sessioni.values().removeIf(s -> !ora.isBefore(s.scadenza));
    }

    /** Informazioni HKDF: legano la chiave allo scambio, al profilo e all'azione. */
    static byte[] info(String scambioId, String profiloId, String azione) {
        return ("DASS-scambio-v1|" + scambioId + "|" + profiloId + "|" + azione).getBytes(StandardCharsets.UTF_8);
    }

    /** HKDF con HMAC-SHA-256 (RFC 5869), lo stesso algoritmo di Web Crypto deriveKey({name: "HKDF"}). */
    static byte[] hkdf(byte[] sale, byte[] materiale, byte[] info, int lunghezza) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(sale.length == 0 ? new byte[32] : sale, "HmacSHA256"));
            byte[] prk = mac.doFinal(materiale);
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            byte[] risultato = new byte[lunghezza];
            byte[] blocco = new byte[0];
            for (int i = 1, posizione = 0; posizione < lunghezza; i++) {
                mac.update(blocco);
                mac.update(info);
                mac.update((byte) i);
                blocco = mac.doFinal();
                int quanti = Math.min(blocco.length, lunghezza - posizione);
                System.arraycopy(blocco, 0, risultato, posizione, quanti);
                posizione += quanti;
            }
            return risultato;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA-256 non disponibile nella JVM.", e);
        }
    }

    /**
     * Converte il punto non compresso inviato dal browser (0x04 || X || Y) in una chiave pubblica,
     * dopo aver verificato che appartenga alla curva P-256: un punto fuori curva è rifiutato.
     */
    static ECPublicKey importaPunto(byte[] raw) {
        if (raw == null || raw.length != 65 || raw[0] != 0x04)
            throw new ErroreAnalisi(400, "CHIAVE_PUBBLICA_NON_VALIDA", "La chiave pubblica deve essere un punto P-256 non compresso di 65 byte.");
        var x = new BigInteger(1, Arrays.copyOfRange(raw, 1, 33));
        var y = new BigInteger(1, Arrays.copyOfRange(raw, 33, 65));
        var p = ((ECFieldFp) P256.getCurve().getField()).getP();
        var a = P256.getCurve().getA();
        var b = P256.getCurve().getB();
        boolean sullaCurva = x.compareTo(p) < 0 && y.compareTo(p) < 0
                && y.multiply(y).mod(p).equals(x.pow(3).add(a.multiply(x)).add(b).mod(p));
        if (!sullaCurva)
            throw new ErroreAnalisi(400, "CHIAVE_PUBBLICA_NON_VALIDA", "Il punto inviato non appartiene alla curva P-256.");
        try {
            return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
        } catch (GeneralSecurityException e) {
            throw new ErroreAnalisi(400, "CHIAVE_PUBBLICA_NON_VALIDA", "Chiave pubblica P-256 non valida.");
        }
    }

    /** Punto non compresso di 65 byte, lo stesso formato di exportKey("raw") in Web Crypto. */
    static byte[] puntoRaw(ECPublicKey chiave) {
        byte[] raw = new byte[65];
        raw[0] = 0x04;
        copiaCoordinata(chiave.getW().getAffineX(), raw, 1);
        copiaCoordinata(chiave.getW().getAffineY(), raw, 33);
        return raw;
    }

    private static void copiaCoordinata(BigInteger valore, byte[] destinazione, int inizio) {
        byte[] byteValore = valore.toByteArray();
        int lunghezza = Math.min(byteValore.length, 32);
        System.arraycopy(byteValore, byteValore.length - lunghezza, destinazione, inizio + 32 - lunghezza, lunghezza);
    }

    /** Impronta leggibile di una chiave pubblica: i primi 64 bit dello SHA-256, in quattro gruppi. */
    public static String impronta(byte[] chiavePubblica) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(chiavePubblica);
            var testo = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                if (i > 0 && i % 2 == 0) testo.append(' ');
                testo.append(String.format("%02x", digest[i]));
            }
            return testo.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ECParameterSpec parametriP256() {
        try {
            var parametri = AlgorithmParameters.getInstance("EC");
            parametri.init(new ECGenParameterSpec("secp256r1"));
            return parametri.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Curva P-256 non disponibile nella JVM.", e);
        }
    }
}
