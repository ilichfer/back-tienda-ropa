package com.tiendaropa.domain.model;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "wa_plantillas")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WaPlantilla {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true, nullable = false, length = 50)
    private String slug;

    @Column(nullable = false, length = 80)
    private String titulo;

    @Column(nullable = false, columnDefinition = "text")
    private String cuerpo;

    @Column(nullable = false)
    @Builder.Default
    private Boolean activa = true;
}
