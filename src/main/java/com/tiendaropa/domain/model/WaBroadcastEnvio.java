package com.tiendaropa.domain.model;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

// Un renglón por destinatario de una campaña de broadcast (WaBroadcast), para auditoría y
// detalle de errores por si alguno falló (ej. destinatario nunca escribió, plantilla
// rechazada, número inválido).
@Entity
@Table(name = "wa_broadcast_envios")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WaBroadcastEnvio {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "broadcast_id", nullable = false)
    private UUID broadcastId;

    @Column(name = "whatsapp_from", nullable = false, length = 50)
    private String whatsappFrom;

    @Column(length = 20)
    @Builder.Default
    private String estado = "PENDIENTE";

    @Column(name = "wa_message_id", length = 120)
    private String waMessageId;

    @Column(columnDefinition = "text")
    private String error;
}
