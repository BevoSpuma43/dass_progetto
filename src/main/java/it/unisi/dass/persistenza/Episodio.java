package it.unisi.dass.persistenza;

import jakarta.persistence.*;

/**
 * Singolo consumo registrato. Conserva foto, risultati e riferimento alla memoria usata per la
 * simulazione.
 */
@Entity
public class Episodio {
    @Id
    String id;

    @ManyToOne(optional = false)
    @JoinColumn(nullable = false)
    Piatto piatto;

    @ManyToOne(optional = false)
    @JoinColumn(nullable = false)
    MemoriaPiatto memoriaOriginale;

    @Column(nullable = false, unique = true)
    String chiaveInvio;

    @Column(length = 64)
    String improntaInvio;

    @Lob
    String anteprimaOriginale;

    @Column(nullable = false)
    long istantePastoEpochSecond;

    @Column(nullable = false)
    String caption;

    @Column(nullable = false)
    String formatoFoto;

    @Lob
    @Column(nullable = false)
    byte[] foto;

    @Lob
    @Column(nullable = false)
    byte[] miniatura;

    @Embedded
    Macronutrienti totali;

    @Column(nullable = false)
    double stimaOriginale;

    @Column(nullable = false)
    String versioneProfilo;

    @Column(nullable = false)
    String versioneRegola;

    @Column(nullable = false)
    String indiceGlicemico;
}
