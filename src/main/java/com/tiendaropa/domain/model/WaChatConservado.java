package com.tiendaropa.domain.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

// Chat antiguo que el equipo revisó y decidió conservar: no vuelve a salir en el aviso de
// chats antiguos hasta que pase otra vez el plazo desde esta fecha.
@Entity
@Table(name = "wa_chats_conservados")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WaChatConservado {

    @Id
    @Column(name = "whatsapp_from", length = 50)
    private String whatsappFrom;

    @Column(name = "conservado_en", nullable = false)
    private Instant conservadoEn;
}
