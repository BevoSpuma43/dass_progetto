package it.unisi.dass.persistenza;

import jakarta.persistence.*;

/** Lettura dimostrativa associata a un episodio, con tempo dal pasto e motivo della valutazione. */
@Entity
public class Feedback {
    @Id
    String id;

    @OneToOne(optional = false)
    @JoinColumn(nullable = false, unique = true)
    Episodio episodio;

    @Column(nullable = false)
    int glicemiaMgDl;

    @Column(nullable = false)
    int minutiDopoPasto;

    @Column(nullable = false)
    long istanteLetturaEpochSecond;

    @Column(nullable = false)
    boolean ammissibile;

    @Column(nullable = false)
    String motivo;
}
