package com.tiendaropa.web.websocket;

import com.tiendaropa.domain.model.Pedido;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Publica cambios de pedidos en tiempo real vía STOMP/WebSocket.
 * El frontend React se suscribe a /topic/pedidos para actualizar
 * la UI sin necesidad de polling.
 */
@Component
@RequiredArgsConstructor
public class PedidoEventPublisher {

    private final SimpMessagingTemplate messaging;

    public void publish(Pedido pedido) {
        messaging.convertAndSend("/topic/pedidos", PedidoWsEvent.from(pedido));
    }

    public record PedidoWsEvent(
        String id,
        Long   numero,
        String estado,
        String clienteNombre,
        String prenda
    ) {
        static PedidoWsEvent from(Pedido p) {
            return new PedidoWsEvent(
                p.getId().toString(),
                p.getNumero(),
                p.getEstado().name(),
                p.getCliente().getNombre(),
                p.getPrenda().getNombre()
            );
        }
    }
}
