package com.tiendaropa.domain.service;

import com.tiendaropa.domain.model.Pedido;
import com.tiendaropa.domain.model.enums.EstadoPedido;
import com.tiendaropa.web.dto.request.PedidoRequest;
import java.util.List;
import java.util.UUID;

public interface PedidoService {

    Pedido crear(PedidoRequest req);

    Pedido cambiarEstado(UUID pedidoId, EstadoPedido nuevoEstado, String nota);

    List<Pedido> listarPorEstado(EstadoPedido estado);
}
