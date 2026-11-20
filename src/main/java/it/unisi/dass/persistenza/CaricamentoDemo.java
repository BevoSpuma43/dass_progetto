package it.unisi.dass.persistenza;

import jakarta.persistence.*;

/**
 * Marcatore persistente che registra il completamento di un caricamento o di una migrazione del
 * catalogo.
 */
@Entity
public class CaricamentoDemo {
    @Id
    String id;

    @Column(nullable = false)
    String versione;
}
