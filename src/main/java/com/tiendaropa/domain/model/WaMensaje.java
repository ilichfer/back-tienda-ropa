package com.tiendaropa.domain.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wa_mensajes")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WaMensaje {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cliente_id")
    private Cliente cliente;

    @Column(name = "whatsapp_from", nullable = false, length = 20)
    private String whatsappFrom;

    @Column(nullable = false, columnDefinition = "text")
    private String contenido;

    @Column(length = 20)
    @Builder.Default
    private String tipo = "text";

    @Column(nullable = false, length = 10)
    private String direccion;

    @Column(length = 120)
    private String waMessageId;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;
}
