package com.tiendaropa.domain.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "solicitudes_envio")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SolicitudEnvio {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cliente_id")
    private Cliente cliente;

    @Column(nullable = false, length = 20)
    private String whatsapp;

    @Column(length = 120)
    private String nombreCompleto;

    @Column(length = 20)
    private String telefono;

    @Column(length = 30)
    private String cedula;

    @Column(columnDefinition = "text")
    private String direccion;

    @Column(length = 80)
    private String ciudad;

    @Column(length = 80)
    private String barrio;

    @Column(columnDefinition = "text")
    private String notas;

    @Column(length = 20)
    @Builder.Default
    private String estado = "PENDIENTE";

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @Column(insertable = false, updatable = false)
    private Instant updatedAt;
}
