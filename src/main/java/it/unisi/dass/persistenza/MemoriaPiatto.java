package it.unisi.dass.persistenza;

import it.unisi.dass.servizi.AnteprimaPasto;
import jakarta.persistence.*;

/** Versione dei carboidrati efficaci e degli altri dati dimostrativi associati a un piatto abituale. */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"PIATTO_ID", "NUMERO"}))
public class MemoriaPiatto {
    @Id
    String id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "PIATTO_ID", nullable = false)
    Piatto piatto;

    @Column(nullable = false)
    int numero;

    @Column(nullable = false)
    double carboidratiEfficaciG;

    @Column(nullable = false)
    double varianza;

    @Column(nullable = false)
    int segnaliPositiviConsecutivi;

    @Column(nullable = false)
    int ultimoSegno;

    /** Estrae i dati della versione di memoria in un record utilizzabile dal calcolo. */
    AnteprimaPasto.Memoria perSimulazione() {
        return new AnteprimaPasto.Memoria(id, numero, piatto.memoriaInizialeG, carboidratiEfficaciG, varianza);
    }
}
