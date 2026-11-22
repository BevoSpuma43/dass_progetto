package it.unisi.dass.persistenza;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

/**
 * Documento del diario cifrato nel browser con AES-GCM. In H2 restano solo i metadati minimi per
 * associarlo al profilo (id, tipo, revisione, data di aggiornamento) e il testo cifrato, che Payara non
 * può decifrare.
 */
@Entity
@Table(name = "DOCUMENTO_CIFRATO", indexes = @Index(name = "IDX_DOCUMENTO_PROFILO_TIPO", columnList = "PROFILO_ID, TIPO"))
public class DocumentoCifrato {
    @Id
    @Column(length = 64)
    String id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "PROFILO_ID", nullable = false)
    Profilo profilo;

    @Column(nullable = false, length = 20)
    String tipo;

    @Column(nullable = false)
    long revisione;

    @Column(nullable = false, length = 12)
    byte[] iv;

    @Lob
    @Column(nullable = false)
    byte[] dati;

    @Column(nullable = false)
    long aggiornatoAlleEpochMilli;

    /** Il documento come è conservato in H2: metadati e testo cifrato in Base64. */
    JsonObject json() {
        return Json.createObjectBuilder().add("id", id).add("tipo", tipo).add("revisione", revisione)
                .add("iv", Base64.getEncoder().encodeToString(iv)).add("dati", Base64.getEncoder().encodeToString(dati))
                .add("aggiornatoAlle", Instant.ofEpochMilli(aggiornatoAlleEpochMilli).toString()).build();
    }

    /** Ricevuta di un salvataggio, senza il contenuto. */
    JsonObject ricevuta() {
        return Json.createObjectBuilder().add("id", id).add("tipo", tipo).add("revisione", revisione)
                .add("aggiornatoAlle", Instant.ofEpochMilli(aggiornatoAlleEpochMilli).toString()).build();
    }

    /** Riga della vista di H2: metadati, lunghezza e i primi 48 byte del testo cifrato. */
    JsonObject riga() {
        byte[] estratto = Arrays.copyOf(dati, Math.min(dati.length, 48));
        return Json.createObjectBuilder().add("profilo", profilo.id).add("id", id).add("tipo", tipo).add("revisione", revisione)
                .add("iv", Base64.getEncoder().encodeToString(iv)).add("byte", dati.length)
                .add("estratto", Base64.getEncoder().encodeToString(estratto))
                .add("aggiornatoAlle", Instant.ofEpochMilli(aggiornatoAlleEpochMilli).toString()).build();
    }

    /** Il documento completo con il profilo a cui appartiene, per la prova di decifratura. */
    JsonObject grezzo() {
        return Json.createObjectBuilder(json()).add("profilo", profilo.id).build();
    }
}
