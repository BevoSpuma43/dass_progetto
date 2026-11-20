package it.unisi.dass.servizi;

import it.unisi.dass.analisi.ErroreAnalisi;
import jakarta.json.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Set;

/** Dati validati di una conferma, usati per la registrazione e per riconoscere i retry. */
public record RichiestaPasto(String richiestaId, String bozzaId, long revisione, Instant consumatoAlle,
                             String piattoId, String nomePiatto, JsonArray ingredienti) {
    private static final Set<String> CAMPI = Set.of("richiestaId", "bozzaId", "revisione", "consumatoAlle",
            "piattoId", "nomePiatto", "ingredienti");

    /**
     * Valida il corpo JSON, gli ID, la revisione e la scelta tra piatto nuovo e abituale. Restituisce
     * i dati normalizzati della conferma.
     */
    public static RichiestaPasto leggi(JsonObject json) {
        if (json == null || !CAMPI.containsAll(json.keySet())
                || !(json.get("revisione") instanceof JsonNumber n) || !n.isIntegral()
                || !(json.get("ingredienti") instanceof JsonArray righe) || righe.size() > 30) {
            throw errore("PASTO_NON_VALIDO", "Invia revisione, ingredienti e dati di registrazione del pasto.");
        }
        String richiesta = identificativo(json, "richiestaId", true);
        String bozza = identificativo(json, "bozzaId", true);
        String piatto = identificativo(json, "piattoId", false);
        String nome = testo(json, "nomePiatto", null, 200);
        if ((piatto == null) == (nome == null))
            throw errore("PIATTO_NON_VALIDO", "Scegli un piatto abituale oppure indica il nome del nuovo piatto.");
        long revisione;
        try {
            revisione = n.longValueExact();
            if (revisione < 0) throw new ArithmeticException();
        } catch (ArithmeticException e) {
            throw errore("REVISIONE_NON_VALIDA", "Indica la revisione corrente della bozza.");
        }
        return new RichiestaPasto(richiesta, bozza, revisione, orario(json), piatto, nome, preparaRighe(righe));
    }

    /** Legge data e ora ISO 8601 con offset e le converte in un istante indipendente dal fuso locale. */
    public static Instant orario(JsonObject json) {
        try {
            return OffsetDateTime.parse(testo(json, "consumatoAlle", "", 64)).toInstant();
        } catch (DateTimeParseException e) {
            throw errore("ORARIO_NON_VALIDO", "Indica data e ora del pasto con fuso orario in formato ISO 8601.");
        }
    }

    /**
     * Costruisce le righe normalizzate da usare nel calcolo e nell'impronta, controllando i pesi
     * positivi quando presenti.
     */
    private static JsonArray preparaRighe(JsonArray righe) {
        var risultato = Json.createArrayBuilder();
        for (var valore : righe) {
            if (!(valore instanceof JsonObject riga)) throw rigaNonValida();
            String id = identificativo(riga, "rigaId", false);
            String nome = testo(riga, "nome", null, 500);
            String voce = identificativo(riga, "voceCatalogoId", false);
            String stato = testo(riga, "stato", "non_noto", 20);
            if (nome == null || !Set.of("non_noto", "crudo", "cotto").contains(stato)) throw rigaNonValida();
            var pulita = Json.createObjectBuilder().add("nome", nome).add("stato", stato)
                    .add("rigaId", id == null ? "" : id);
            if (voce == null) pulita.addNull("voceCatalogoId");
            else pulita.add("voceCatalogoId", voce);
            var peso = riga.getOrDefault("quantitaG", JsonValue.NULL);
            if (peso == JsonValue.NULL) {
                pulita.addNull("quantitaG");
            } else if (peso instanceof JsonNumber grammi && grammi.doubleValue() > 0
                    && Double.isFinite(grammi.doubleValue())) {
                pulita.add("quantitaG", grammi.bigDecimalValue().stripTrailingZeros());
            } else {
                throw rigaNonValida();
            }
            risultato.add(pulita);
        }
        return risultato.build();
    }

    /**
     * Controlla formato e lunghezza di un ID, distinguendo tra riferimenti obbligatori e facoltativi.
     */
    private static String identificativo(JsonObject json, String campo, boolean obbligatorio) {
        String valore = testo(json, campo, null, 128);
        if (valore == null && !obbligatorio) return null;
        if (valore == null || !valore.matches("[A-Za-z0-9_-]+"))
            throw errore("PASTO_NON_VALIDO", "Controlla il campo " + campo + ".");
        return valore;
    }

    /**
     * Legge una stringa JSON, elimina gli spazi ai bordi e applica il limite di lunghezza e il valore
     * predefinito.
     */
    private static String testo(JsonObject json, String campo, String predefinito, int massimo) {
        var valore = json.getOrDefault(campo, JsonValue.NULL);
        if (valore == JsonValue.NULL) return predefinito;
        if (!(valore instanceof JsonString testo) || testo.getString().length() > massimo)
            throw errore("PASTO_NON_VALIDO", "Controlla il campo " + campo + ".");
        String pulito = testo.getString().strip();
        return pulito.isEmpty() ? predefinito : pulito;
    }

    /** Ordine delle proprietà, cifre decimali finali e offset equivalenti non cambiano la conferma. */
    public String impronta() {
        var contenuto = Json.createObjectBuilder().add("bozzaId", bozzaId).add("revisione", revisione)
                .add("consumatoAlle", consumatoAlle.toString()).add("ingredienti", ingredienti);
        if (piattoId == null) contenuto.add("nomePiatto", nomePiatto);
        else contenuto.add("piattoId", piattoId);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(contenuto.build().toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 non disponibile.", e);
        }
    }

    /** Prepara l'errore per un ingrediente con nome, preparazione o peso non validi. */
    private static ErroreAnalisi rigaNonValida() {
        return errore("RIGA_NON_VALIDA", "Controlla nome, preparazione e peso positivo degli ingredienti.");
    }

    /** Costruisce un errore HTTP 400 con il codice e il messaggio applicativi. */
    private static ErroreAnalisi errore(String codice, String messaggio) {
        return new ErroreAnalisi(400, codice, messaggio);
    }
}
