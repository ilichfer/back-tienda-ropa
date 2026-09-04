package com.tiendaropa.domain.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

// Una "campaña" de envío masivo: qué plantilla de Meta se usó y con qué configuración de
// variables (texto fijo para todos, o personalizado por destinatario — ver
// BroadcastServiceImpl). Los contadores se actualizan a medida que se procesa cada
// destinatario en wa_broadcast_envios.
@Entity
@Table(name = "wa_broadcasts")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WaBroadcast {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "plantilla_meta_id")
    private UUID plantillaMetaId;

    // JSON simple: { "nombre": "fijo:Hola a todos" } o { "nombre": "cliente_nombre" } para
    // personalizar por destinatario con el nombre de cada Cliente.
    @Column(name = "variables_config", columnDefinition = "text")
    private String variablesConfig;

    @Builder.Default
    private Integer total = 0;

    @Builder.Default
    private Integer enviados = 0;

    @Builder.Default
    private Integer fallidos = 0;

    @Column(length = 20)
    @Builder.Default
    private String estado = "PENDIENTE";

    @Column(insertable = false, updatable = false)
    private Instant createdAt;
}
