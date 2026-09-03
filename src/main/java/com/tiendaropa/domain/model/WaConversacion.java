package com.tiendaropa.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Estado persistido de una conversación de WhatsApp en curso (flujo ENVIO / PEDIDO /
 * INCONVENIENTE), para que sobreviva a un reinicio del backend.
 *
 * El detalle recolectado durante la conversación (nombre, dirección, fotos, etc.) se guarda
 * serializado como JSON en {@code datos} — es exactamente el mismo objeto que hoy vive en
 * memoria en {@code WhatsAppServiceImpl.ConversacionCliente}, solo que también queda en BD.
 */
@Entity
@Table(name = "wa_conversaciones")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WaConversacion {

    @Id
    @Column(name = "whatsapp_from")
    private String whatsappFrom;

    private String flujo;

    private Integer paso;

    @Column(columnDefinition = "TEXT")
    private String datos;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
