package com.tiendaropa.domain.model;

import com.tiendaropa.domain.model.enums.EstadoPrenda;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "prendas")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Prenda {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lote_id", nullable = false)
    private Lote lote;

    @Column(nullable = false, length = 120)
    private String nombre;

    @Column(length = 10)
    private String talla;

    @Column(length = 50)
    private String color;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal precio;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "estado_prenda")
    @Builder.Default
    private EstadoPrenda estado = EstadoPrenda.DISPONIBLE;

    @Column(length = 500)
    private String fotoUrl;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @Column(insertable = false, updatable = false)
    private Instant updatedAt;
}
