package com.tiendaropa.domain.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

// Nota interna sobre una conversación de WhatsApp: solo la ven los asesores, nunca se le
// manda al cliente. No hay tabla de usuarios en el sistema, así que "autor" es texto libre
// opcional en vez de una relación forzada a un usuario logueado.
@Entity
@Table(name = "wa_notas")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WaNota {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "whatsapp_from", nullable = false, length = 50)
    private String whatsappFrom;

    @Column(length = 80)
    private String autor;

    @Column(nullable = false, columnDefinition = "text")
    private String contenido;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;
}
