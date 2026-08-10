package com.tiendaropa.domain.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cuentas_movimientos")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CuentaMovimiento {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cuenta_id", nullable = false)
    private Cuenta cuenta;

    @Column(nullable = false, length = 10)
    private String tipo;

    @Column(length = 200)
    private String concepto;

    private Long valor;

    @Column(nullable = false, length = 30)
    @Builder.Default
    private String estado = "PENDIENTE";

    @Column(length = 120)
    private String referencia;

    @Column(length = 30)
    private String metodo;

    @Column(length = 255)
    private String mediaId;

    @Column(length = 500)
    private String mediaPath;

    @Column(length = 80)
    private String mimeType;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;
}
