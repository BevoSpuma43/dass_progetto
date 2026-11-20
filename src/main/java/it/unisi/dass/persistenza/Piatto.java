package it.unisi.dass.persistenza;

import jakarta.persistence.*;

/**
 * Identità riutilizzabile di un alimento o piatto nel diario, distinta dai singoli episodi di consumo.
 */
@Entity
public class Piatto {
    @Id
    String id;

    @ManyToOne(optional = false)
    @JoinColumn(nullable = false)
    Paziente paziente;

    @Column(nullable = false)
    String nome;

    @Column(nullable = false)
    double memoriaInizialeG;

    @Version
    long versioneLock;
}
