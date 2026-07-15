package com.tiendaropa.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaropa.domain.model.enums.EstadoPedido;
import com.tiendaropa.domain.service.PedidoService;
import com.tiendaropa.domain.service.WhatsAppService;
import com.tiendaropa.web.dto.request.CambiarEstadoRequest;
import com.tiendaropa.web.dto.request.PedidoRequest;
import com.tiendaropa.web.dto.response.PedidoResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PedidoController {

    private final PedidoService   pedidoService;
    private final WhatsAppService whatsAppService;

    @Value("${whatsapp.verify-token}")
    private String verifyToken;

    @GetMapping("/pedidos")
    public List<PedidoResponse> listar(@RequestParam(required = false) EstadoPedido estado) {
        return pedidoService.listarPorEstado(estado)
                .stream()
                .map(PedidoResponse::from)
                .toList();
    }

    @PostMapping("/pedidos")
    public ResponseEntity<PedidoResponse> crear(@Valid @RequestBody PedidoRequest req) {
        var pedido = pedidoService.crear(req);
        return ResponseEntity.ok(PedidoResponse.from(pedido));
    }

    @PatchMapping("/pedidos/{id}/estado")
    public ResponseEntity<PedidoResponse> cambiarEstado(
            @PathVariable UUID id,
            @Valid @RequestBody CambiarEstadoRequest req) {
        var pedido = pedidoService.cambiarEstado(id, req.estado(), req.nota());
        return ResponseEntity.ok(PedidoResponse.from(pedido));
    }

    @GetMapping("/webhook/whatsapp")
    public ResponseEntity<String> verificarWebhook(HttpServletRequest request) {
        var mode = request.getParameter("hub.mode");
        var challenge = request.getParameter("hub.challenge");
        var token = request.getParameter("hub.verify_token");

        if ("subscribe".equals(mode) && verifyToken.equals(token)) {
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(403).body("Forbidden");
    }

    @PostMapping("/webhook/whatsapp")
    public ResponseEntity<String> recibirWebhook(@RequestBody JsonNode payload) {
        whatsAppService.procesarWebhook(payload);
        return ResponseEntity.ok("EVENT_RECEIVED");
    }
}
