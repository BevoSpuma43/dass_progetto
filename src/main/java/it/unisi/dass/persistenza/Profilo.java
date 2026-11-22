package it.unisi.dass.persistenza;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.persistence.*;

/**
 * Profilo del diario. Payara ne conosce solo identificativo e nome: pasti, bozze, foto e parametri del
 * bolo arrivano già cifrati dal browser con la chiave del profilo, che Payara non possiede.
 */
@Entity
public class Profilo {
    @Id
    @Column(length = 32)
    String id;

    @Column(nullable = false)
    String nome;

    /** Costruttore senza argomenti richiesto da JPA. */
    protected Profilo() {}

    Profilo(String id, String nome) {
        this.id = id;
        this.nome = nome;
    }

    /** Identificativo e nome del profilo, gli unici dati che l'elenco pubblico espone. */
    JsonObject json(long documenti) {
        return Json.createObjectBuilder().add("id", id).add("nome", nome).add("documenti", documenti).build();
    }
}
