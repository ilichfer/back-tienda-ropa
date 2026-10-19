package com.tiendaropa.domain.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "clientes")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Cliente {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true, nullable = false, length = 20)
    private String whatsapp;

    @Column(length = 120)
    private String nombre;

    @Column(length = 80)
    private String ciudad;

    @Column(columnDefinition = "text")
    private String direccion;

    @Builder.Default
    private Boolean requiereAsesor = false;

    // Desde cuándo espera a un asesor; lo mantiene setRequiereAsesor, no se escribe a mano.
    private Instant asesorSolicitadoEn;

    // Aceptó recibir por WhatsApp el aviso del próximo live (botón "🔔 Avisarme").
    @Builder.Default
    private Boolean avisosLive = false;

    // Interruptor manual del operador: en true, el bot (IA + flujos automáticos) no
    // responde nada en esta conversación hasta que un humano lo vuelva a activar.
    // Independiente de requiereAsesor, que es una señal automática.
    @Builder.Default
    private Boolean botSilenciado = false;

    // Marca un chat como la fuente de donde llegan las fotos de guías de envío (no es un
    // cliente real) — ver WaMensajeController.marcarBuzonGuias.
    @Builder.Default
    private Boolean esBuzonGuias = false;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @Column(insertable = false, updatable = false)
    private Instant updatedAt;

    // requiereAsesor se marca desde varios lugares (bot, panel, ecos del celular): la hora de
    // la espera se mantiene acá para que ninguno tenga que acordarse de ponerla.
    public void setRequiereAsesor(Boolean requiereAsesor) {
        var antes = Boolean.TRUE.equals(this.requiereAsesor);
        var ahora = Boolean.TRUE.equals(requiereAsesor);
        if (ahora && (!antes || asesorSolicitadoEn == null)) asesorSolicitadoEn = Instant.now();
        if (!ahora) asesorSolicitadoEn = null;
        this.requiereAsesor = requiereAsesor;
    }
}
