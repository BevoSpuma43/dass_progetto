package it.unisi.dass.sicurezza;

import it.unisi.dass.analisi.ErroreAnalisi;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Formato del canale cifrato di un'azione. Ogni messaggio è AES-GCM con un IV casuale di 12 byte e
 * dati aggiuntivi autenticati che lo legano allo scambio e alla singola richiesta:
 * <ul>
 *   <li>descrittore della richiesta (metodo, percorso, nonce, istante): {@code DASS|richiesta|<scambio>};</li>
 *   <li>corpo della richiesta: {@code DASS|corpo|<scambio>|<nonce>};</li>
 *   <li>corpo della risposta: {@code DASS|risposta|<scambio>|<nonce>}.</li>
 * </ul>
 */
public final class CanaleCifrato {
    public static final String INTESTAZIONE_SCAMBIO = "X-DASS-Scambio";
    public static final String INTESTAZIONE_BUSTA = "X-DASS-Busta";
    public static final String INTESTAZIONE_CIFRATO = "X-DASS-Cifrato";
    /** Scostamento massimo tra l'orologio del browser e quello di Payara. */
    public static final long FINESTRA_MS = 120_000;
    private static final SecureRandom CASUALE = new SecureRandom();

    /** Impedisce l'istanziazione di questa classe di utilità. */
    private CanaleCifrato() {}

    static String aadRichiesta(String scambio) {
        return "DASS|richiesta|" + scambio;
    }

    static String aadCorpo(String scambio, String nonce) {
        return "DASS|corpo|" + scambio + "|" + nonce;
    }

    static String aadRisposta(String scambio, String nonce) {
        return "DASS|risposta|" + scambio + "|" + nonce;
    }

    static byte[] nuovoIv() {
        byte[] iv = new byte[12];
        CASUALE.nextBytes(iv);
        return iv;
    }

    /** Cifra con AES-GCM a 128 bit di tag: il risultato è testo cifrato seguito dal tag, come in Web Crypto. */
    static byte[] cifra(SecretKey chiave, byte[] iv, byte[] chiaro, String aad) {
        try {
            var cifrario = Cipher.getInstance("AES/GCM/NoPadding");
            cifrario.init(Cipher.ENCRYPT_MODE, chiave, new GCMParameterSpec(128, iv));
            cifrario.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return cifrario.doFinal(chiaro);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM non disponibile nella JVM.", e);
        }
    }

    /** Decifra e verifica il tag: un messaggio alterato, una chiave diversa o dati aggiuntivi diversi falliscono. */
    static byte[] decifra(SecretKey chiave, byte[] iv, byte[] cifrato, String aad) {
        try {
            if (iv.length != 12) throw new AEADBadTagException("IV di lunghezza errata");
            var cifrario = Cipher.getInstance("AES/GCM/NoPadding");
            cifrario.init(Cipher.DECRYPT_MODE, chiave, new GCMParameterSpec(128, iv));
            cifrario.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return cifrario.doFinal(cifrato);
        } catch (AEADBadTagException e) {
            throw new ErroreAnalisi(400, "BUSTA_NON_VALIDA", "Messaggio cifrato non valido per questo scambio di chiavi.");
        } catch (GeneralSecurityException e) {
            throw new ErroreAnalisi(400, "BUSTA_NON_VALIDA", "Messaggio cifrato illeggibile.");
        }
    }
}
