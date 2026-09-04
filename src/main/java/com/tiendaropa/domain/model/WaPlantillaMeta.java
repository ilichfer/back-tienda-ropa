package com.tiendaropa.domain.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

// Registro local de una plantilla YA aprobada por Meta en WhatsApp Manager — la app no crea
// ni aprueba plantillas ante Meta, solo necesita saber que existe, en qué idioma y qué
// variables pide, para poder dispararla en un envío masivo (BroadcastService). No confundir
// con WaPlantilla, que son respuestas rápidas de texto libre para el chat 1:1.
@Entity
@Table(name = "wa_plantillas_meta")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WaPlantillaMeta {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Debe coincidir EXACTO con el nombre de la plantilla aprobada en WhatsApp Manager.
    @Column(unique = true, nullable = false, length = 120)
    private String nombre;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String idioma = "es";

    // Etiquetas de las variables {{1}}, {{2}}... en orden, separadas por coma (ej. "nombre,guia").
    @Column(length = 300)
    private String variables;

    @Column(columnDefinition = "text")
    private String descripcion;

    @Builder.Default
    private Boolean activa = true;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;
}
