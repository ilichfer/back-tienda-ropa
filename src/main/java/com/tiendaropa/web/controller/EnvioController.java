package com.tiendaropa.web.controller;

import com.tiendaropa.domain.service.EnvioService;
import com.tiendaropa.web.dto.request.EnvioEstadoRequest;
import com.tiendaropa.web.dto.response.EnvioResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/envios")
@RequiredArgsConstructor
public class EnvioController {

    private final EnvioService envioService;

    @GetMapping
    public ResponseEntity<List<EnvioResponse>> listar(
            @RequestParam(required = false) String estado) {
        return ResponseEntity.ok(envioService.listar(estado));
    }

    @PatchMapping("/{id}/estado")
    public ResponseEntity<EnvioResponse> cambiarEstado(
            @PathVariable UUID id,
            @Valid @RequestBody EnvioEstadoRequest req) {
        return ResponseEntity.ok(envioService.cambiarEstado(id, req.estado()));
    }
}
