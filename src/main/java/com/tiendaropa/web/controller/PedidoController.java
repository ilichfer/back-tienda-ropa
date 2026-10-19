package com.tiendaropa.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiendaropa.domain.model.enums.EstadoPedido;
import com.tiendaropa.domain.service.PedidoService;
import com.tiendaropa.domain.service.WhatsAppService;
import com.tiendaropa.domain.service.whatsapp.FirmaWebhookMeta;
import com.tiendaropa.web.dto.request.CambiarEstadoRequest;
import com.tiendaropa.web.dto.request.PedidoRequest;
import com.tiendaropa.web.dto.response.PedidoResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class PedidoController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PedidoService   pedidoService;
    private final WhatsAppService whatsAppService;
    private final FirmaWebhookMeta firmaWebhook;

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

    @PostMapping("/pedidos/bodega")
    public ResponseEntity<PedidoResponse> crearBodega(@RequestBody Map<String, String> body) {
        // "whatsapp" ata el pedido a un chat/cliente existente (ver Bodega.tsx, el
        // desplegable de "Nombre" ahora envía el número del chat seleccionado en vez
        // de texto libre).
        var pedido = pedidoService.crearBodega(body.get("whatsapp"), body.get("ubicacion"));
        return ResponseEntity.status(HttpStatus.CREATED).body(PedidoResponse.from(pedido));
    }

    @PatchMapping("/pedidos/{id}/estado")
    public ResponseEntity<PedidoResponse> cambiarEstado(
            @PathVariable UUID id,
            @Valid @RequestBody CambiarEstadoRequest req) {
        var pedido = pedidoService.cambiarEstado(id, req.estado(), req.nota());
        return ResponseEntity.ok(PedidoResponse.from(pedido));
    }

    @PatchMapping("/pedidos/{id}/bodega")
    public ResponseEntity<PedidoResponse> actualizarBodega(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        var pedido = pedidoService.actualizarBodega(
                id,
                body.get("nombreDueño"),
                body.get("ubicacion"));
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

    // Se recibe el cuerpo crudo (bytes) porque la firma de Meta se calcula sobre el cuerpo
    // exacto: si se dejara que Spring lo convierta a JSON primero, ya no se podría verificar.
    @PostMapping("/webhook/whatsapp")
    public ResponseEntity<String> recibirWebhook(
            @RequestBody byte[] cuerpo,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String firma) throws java.io.IOException {
        if (!firmaWebhook.esValida(cuerpo, firma)) {
            log.warn("[WEBHOOK] Webhook rechazado: firma inválida o ausente ({} bytes)", cuerpo.length);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Firma inválida");
        }
        whatsAppService.procesarWebhook(JSON.readTree(cuerpo));
        return ResponseEntity.ok("EVENT_RECEIVED");
    }
}
