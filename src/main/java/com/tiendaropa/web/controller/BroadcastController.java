package com.tiendaropa.web.controller;

import com.tiendaropa.domain.repository.WaBroadcastEnvioRepository;
import com.tiendaropa.domain.repository.WaBroadcastRepository;
import com.tiendaropa.domain.service.BroadcastService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/broadcasts")
@RequiredArgsConstructor
public class BroadcastController {

    private final BroadcastService broadcastService;
    private final WaBroadcastRepository broadcastRepo;
    private final WaBroadcastEnvioRepository envioRepo;

    @GetMapping
    public List<?> listar() {
        return broadcastRepo.findAllByOrderByCreatedAtDesc();
    }

    @SuppressWarnings("unchecked")
    @PostMapping
    public ResponseEntity<?> crear(@RequestBody Map<String, Object> body) {
        var plantillaMetaId = UUID.fromString((String) body.get("plantillaMetaId"));
        var variablesConfig = (Map<String, String>) body.getOrDefault("variablesConfig", Map.of());
        var destinatarios = (List<String>) body.getOrDefault("destinatarios", List.of());
        var broadcast = broadcastService.crearYEjecutar(plantillaMetaId, variablesConfig, destinatarios);
        return ResponseEntity.status(HttpStatus.CREATED).body(broadcast);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detalle(@PathVariable UUID id) {
        var broadcast = broadcastRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Difusión no encontrada: " + id));
        return Map.of(
                "broadcast", broadcast,
                "envios", envioRepo.findByBroadcastId(id)
        );
    }
}
