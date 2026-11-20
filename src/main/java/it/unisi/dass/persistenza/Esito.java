package it.unisi.dass.persistenza;

import jakarta.persistence.*;

/**
 * Entità delle fixture che collega una lettura alle versioni di memoria prima e dopo la valutazione
 * dimostrativa.
 */
@Entity
public class Esito {
    @Id
    String id;

    @OneToOne(optional = false)
    @JoinColumn(nullable = false, unique = true)
    Feedback feedback;

    @ManyToOne(optional = false)
    @JoinColumn(nullable = false)
    MemoriaPiatto precedente;

    @ManyToOne(optional = false)
    @JoinColumn(nullable = false)
    MemoriaPiatto successiva;

    @Column(nullable = false)
    String versioneRegola;

    @Column(nullable = false)
    String motivo;
}
