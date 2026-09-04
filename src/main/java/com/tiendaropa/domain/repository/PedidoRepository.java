package com.tiendaropa.domain.repository;

import com.tiendaropa.domain.model.Pedido;
import com.tiendaropa.domain.model.enums.EstadoPedido;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface PedidoRepository extends JpaRepository<Pedido, UUID> {

    List<Pedido> findByEstadoOrderByCreatedAtDesc(EstadoPedido estado);

    @Query("SELECT p FROM Pedido p ORDER BY p.createdAt DESC")
    List<Pedido> findAllOrderByCreatedAtDesc();

    // Historial de pedidos de un cliente: le da al agente IA datos reales para responder
    // "¿en qué va mi pedido?" en vez de tener que inventar o escalar siempre esa pregunta.
    List<Pedido> findByCliente_WhatsappOrderByCreatedAtDesc(String whatsapp);
}
