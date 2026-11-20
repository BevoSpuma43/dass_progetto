package it.unisi.dass.persistenza;

import jakarta.persistence.*;

/** Entità temporanea con foto, descrizione, proposta modificabile, scadenza e revisione. */
@Entity
@Cacheable(false)
public class Bozza {
    @Id
    String id;
    @Lob @Column(nullable = false)
    byte[] foto;
    @Column(nullable = false, length = 500)
    String caption;
    @Column(nullable = false)
    long scadeEpochSecond;
    @Lob @Column(nullable = false)
    String proposta;
    @Column(nullable = false)
    long revisione;

    /** Sostituisce il JSON della proposta e incrementa la revisione letta dai client. */
    void aggiornaProposta(String proposta) {
        this.proposta = proposta;
        this.revisione++;
    }
}
