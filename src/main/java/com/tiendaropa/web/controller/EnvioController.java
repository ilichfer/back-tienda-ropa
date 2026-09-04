package com.tiendaropa.web.controller;

import com.tiendaropa.domain.service.EnvioService;
import com.tiendaropa.domain.service.WhatsAppService;
import com.tiendaropa.web.dto.request.EnvioEstadoRequest;
import com.tiendaropa.web.dto.response.EnvioResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/envios")
@RequiredArgsConstructor
@Slf4j
public class EnvioController {

    private final EnvioService envioService;
    private final WhatsAppService whatsAppService;

    @GetMapping
    public ResponseEntity<List<EnvioResponse>> listar(
            @RequestParam(required = false) String estado) {
        return ResponseEntity.ok(envioService.listar(estado));
    }

    @PatchMapping("/{id}/estado")
    public ResponseEntity<EnvioResponse> cambiarEstado(
            @PathVariable UUID id,
            @Valid @RequestBody EnvioEstadoRequest req) {
        var envio = envioService.cambiarEstado(id, req.estado());

        // Igual que al marcar un pedido como enviado: se avisa por WhatsApp que ya salió y
        // que la guía llega después. Va acá (no en el service) para no crear una dependencia
        // circular: WhatsAppServiceImpl ya depende de EnvioService.
        if ("ENVIADO".equals(req.estado())) {
            try {
                whatsAppService.enviarAvisoEnviadoSinGuia(envio.whatsapp(), envio.nombreCompleto());
            } catch (Exception e) {
                log.error("[ENVIO] No se pudo avisar por WhatsApp a {} que su solicitud de envío {} fue enviada: {}",
                    envio.whatsapp(), envio.id(), e.getMessage(), e);
            }
        }

        return ResponseEntity.ok(envio);
    }
}
