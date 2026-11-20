package it.unisi.dass.persistenza;

import jakarta.persistence.*;

/**
 * Riga persistita di un pasto, collegata a una scheda del catalogo con quantità e provenienza del
 * peso.
 */
@Entity
public class Ingrediente {
    @Id
    String id;

    @ManyToOne(optional = false)
    @JoinColumn(nullable = false)
    Episodio episodio;

    @ManyToOne(optional = false)
    @JoinColumn(nullable = false)
    VoceCatalogo voce;

    @Column(nullable = false)
    double quantitaG;

    @Column(nullable = false)
    String origineQuantita;
}
