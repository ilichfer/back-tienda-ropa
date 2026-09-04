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

    @Column(name = "whatsapp_from", nullable = false, length = 50)
    private String whatsappFrom;

    @Column(nullable = false, columnDefinition = "text")
    private String contenido;

    @Column(length = 50)
    @Builder.Default
    private String tipo = "text";

    @Column(nullable = false, length = 10)
    private String direccion;

    @Column(length = 120)
    private String waMessageId;

    // wa_message_id del mensaje que este está citando/respondiendo (la acción de WhatsApp Web
    // de "responder citando"), o null si es un mensaje normal sin cita.
    @Column(name = "context_wa_message_id", length = 120)
    private String contextWaMessageId;

    @Column(length = 255)
    private String mediaId;

    @Column(length = 500)
    private String mediaPath;

    @Column(length = 80)
    private String mimeType;

    @Column(name = "leido")
    @Builder.Default
    private Boolean leido = false;

    // Estado de entrega reportado por el webhook de "statuses" de Meta: sent, delivered,
    // read o failed. Null mientras no llegue ninguna confirmación (o para mensajes ENTRADA,
    // que nunca tienen estado de entrega propio).
    @Column(name = "estado_entrega", length = 20)
    private String estadoEntrega;

    // Motivo cuando estadoEntrega = "failed" (ej. "plantilla no aprobada", "fuera de ventana
    // de 24h"), tomado de errors[0].title del webhook de statuses.
    @Column(name = "error_entrega", columnDefinition = "text")
    private String errorEntrega;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;
}
