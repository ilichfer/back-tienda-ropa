package com.tiendaropa.domain.service.impl;

import com.tiendaropa.domain.model.*;
import com.tiendaropa.domain.model.enums.EstadoPedido;
import com.tiendaropa.domain.model.enums.EstadoPrenda;
import com.tiendaropa.domain.repository.*;
import com.tiendaropa.domain.service.PedidoService;
import com.tiendaropa.domain.service.WhatsAppService;
import com.tiendaropa.web.dto.request.PedidoRequest;
import com.tiendaropa.web.websocket.PedidoEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PedidoServiceImpl implements PedidoService {

    private final PedidoRepository      pedidoRepo;
    private final PrendaRepository      prendaRepo;
    private final ClienteRepository     clienteRepo;
    private final PedidoEventoRepository eventoRepo;
    private final WhatsAppService       whatsAppService;
    private final PedidoEventPublisher  eventPublisher;

    private static final Map<EstadoPedido, List<EstadoPedido>> TRANSICIONES = Map.of(
        EstadoPedido.NUEVO,     List.of(EstadoPedido.APARTADO, EstadoPedido.CANCELADO),
        EstadoPedido.APARTADO,  List.of(EstadoPedido.PAGADO,   EstadoPedido.CANCELADO),
        EstadoPedido.PAGADO,    List.of(EstadoPedido.EMPACADO),
        EstadoPedido.EMPACADO,  List.of(EstadoPedido.ENVIADO),
        EstadoPedido.ENVIADO,   List.of(EstadoPedido.ENTREGADO),
        EstadoPedido.ENTREGADO, List.of(),
        EstadoPedido.CANCELADO, List.of()
    );

    @Override
    @Transactional
    public Pedido crear(PedidoRequest req) {
        var cliente = clienteRepo.findByWhatsapp(req.whatsapp())
                .orElseGet(() -> clienteRepo.save(Cliente.builder()
                        .whatsapp(req.whatsapp())
                        .nombre(req.nombreCliente())
                        .ciudad(req.ciudad())
                        .direccion(req.direccion())
                        .build()));

        var prenda = prendaRepo.findById(req.prendaId())
                .orElseThrow(() -> new IllegalArgumentException("Prenda no encontrada"));

        if (prenda.getEstado() != EstadoPrenda.DISPONIBLE) {
            throw new IllegalStateException("La prenda ya no está disponible");
        }

        var pedido = pedidoRepo.save(Pedido.builder()
                .cliente(cliente)
                .prenda(prenda)
                .precioFinal(prenda.getPrecio())
                .estado(EstadoPedido.NUEVO)
                .build());

        registrarEvento(pedido, EstadoPedido.NUEVO, "Pedido creado");
        eventPublisher.publish(pedido);
        return pedido;
    }

    @Override
    @Transactional
    public Pedido cambiarEstado(UUID pedidoId, EstadoPedido nuevoEstado, String nota) {
        var pedido = pedidoRepo.findById(pedidoId)
                .orElseThrow(() -> new IllegalArgumentException("Pedido no encontrado"));

        validarTransicion(pedido.getEstado(), nuevoEstado);

        pedido.setEstado(nuevoEstado);

        var prenda = pedido.getPrenda();
        switch (nuevoEstado) {
            case APARTADO -> prenda.setEstado(EstadoPrenda.APARTADA);
            case PAGADO   -> prenda.setEstado(EstadoPrenda.PAGADA);
            case ENVIADO  -> {
                prenda.setEstado(EstadoPrenda.ENVIADA);
                whatsAppService.enviarNotificacionEnvio(
                    pedido.getCliente().getWhatsapp(),
                    pedido.getCliente().getNombre(),
                    pedido.getNumeroGuia()
                );
            }
            case CANCELADO -> prenda.setEstado(EstadoPrenda.DISPONIBLE);
        }

        prendaRepo.save(prenda);
        registrarEvento(pedido, nuevoEstado, nota);
        var saved = pedidoRepo.save(pedido);
        eventPublisher.publish(saved);
        return saved;
    }

    @Override
    public List<Pedido> listarPorEstado(EstadoPedido estado) {
        return estado == null
                ? pedidoRepo.findAllOrderByCreatedAtDesc()
                : pedidoRepo.findByEstadoOrderByCreatedAtDesc(estado);
    }

    private void validarTransicion(EstadoPedido actual, EstadoPedido nuevo) {
        if (!TRANSICIONES.getOrDefault(actual, List.of()).contains(nuevo)) {
            throw new IllegalStateException(
                "No se puede pasar de %s a %s".formatted(actual, nuevo));
        }
    }

    private void registrarEvento(Pedido pedido, EstadoPedido estado, String nota) {
        eventoRepo.save(PedidoEvento.builder()
                .pedido(pedido)
                .estado(estado)
                .nota(nota)
                .build());
    }
}
