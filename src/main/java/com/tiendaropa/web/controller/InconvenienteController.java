package com.tiendaropa.web.controller;

import com.tiendaropa.domain.service.InconvenienteService;
import com.tiendaropa.web.dto.response.InconvenienteResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/inconvenientes")
@RequiredArgsConstructor
public class InconvenienteController {

    private final InconvenienteService service;

    @GetMapping
    public ResponseEntity<List<InconvenienteResponse>> listar(
            @RequestParam(required = false) String estado) {
        return ResponseEntity.ok(service.listar(estado));
    }

    @PatchMapping("/{id}/estado")
    public ResponseEntity<InconvenienteResponse> cambiarEstado(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(service.cambiarEstado(id, body.get("estado")));
    }

    @PatchMapping("/{id}/notas")
    public ResponseEntity<InconvenienteResponse> agregarNotas(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(service.agregarNotas(id, body.get("notas")));
    }
}
