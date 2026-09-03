package com.tiendaropa.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inconvenientes")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Inconveniente {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cliente_id")
    private Cliente cliente;

    @Column(nullable = false, length = 20)
    private String whatsapp;

    @Column(nullable = false, length = 20)
    private String tipo;

    @Column(columnDefinition = "text")
    private String descripcion;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String estado = "RECIBIDO";

    @Column(length = 50)
    private String pedidoId;

    @Column(columnDefinition = "text")
    private String fotos;

    @Column(columnDefinition = "text")
    private String notasInternas;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}
