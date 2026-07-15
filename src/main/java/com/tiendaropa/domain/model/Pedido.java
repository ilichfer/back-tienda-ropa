package com.tiendaropa.domain.model;

import com.tiendaropa.domain.model.enums.EstadoPedido;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pedidos")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Pedido {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "numero", insertable = false, updatable = false)
    private Long numero;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cliente_id")
    private Cliente cliente;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "prenda_id")
    private Prenda prenda;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "estado_pedido")
    private EstadoPedido estado = EstadoPedido.NUEVO;

    @Column(name = "precio_final", precision = 12, scale = 2)
    private BigDecimal precioFinal;

    @Column(name = "costo_envio", precision = 12, scale = 2)
    private BigDecimal costoEnvio = new BigDecimal("12000");

    @Column(name = "numero_guia")
    private String numeroGuia;

    private String transportadora;
    private String notas;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}
