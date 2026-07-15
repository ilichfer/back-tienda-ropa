package com.tiendaropa.domain.repository;

import com.tiendaropa.domain.model.PedidoEvento;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PedidoEventoRepository extends JpaRepository<PedidoEvento, UUID> {

    List<PedidoEvento> findByPedidoIdOrderByCreatedAtAsc(UUID pedidoId);
}
