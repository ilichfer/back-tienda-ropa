package com.tiendaropa.domain.model;

import com.tiendaropa.domain.model.enums.EstadoPedido;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pedido_eventos")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PedidoEvento {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pedido_id", nullable = false)
    private Pedido pedido;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "estado_pedido")
    private EstadoPedido estado;

    @Column(columnDefinition = "text")
    private String nota;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;
}
