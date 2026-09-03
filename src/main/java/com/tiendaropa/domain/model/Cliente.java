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

    // Interruptor manual del operador: en true, el bot (IA + flujos automáticos) no
    // responde nada en esta conversación hasta que un humano lo vuelva a activar.
    // Independiente de requiereAsesor, que es una señal automática.
    @Builder.Default
    private Boolean botSilenciado = false;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @Column(insertable = false, updatable = false)
    private Instant updatedAt;
}
