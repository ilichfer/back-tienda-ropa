package com.tiendaropa.web.controller;

import com.tiendaropa.domain.service.InventarioService;
import com.tiendaropa.web.dto.request.LoteRequest;
import com.tiendaropa.web.dto.request.PrendaRequest;
import com.tiendaropa.web.dto.response.LoteResponse;
import com.tiendaropa.web.dto.response.PrendaResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/lotes")
@RequiredArgsConstructor
public class LoteController {

    private final InventarioService inventarioService;

    @GetMapping
    public List<LoteResponse> listar(@RequestParam(required = false) Boolean activos) {
        return inventarioService.listarLotes(activos);
    }

    @PostMapping
    public ResponseEntity<LoteResponse> crear(@Valid @RequestBody LoteRequest req) {
        var lote = inventarioService.crearLote(req);
        var response = inventarioService.obtenerLote(lote.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<LoteResponse> obtener(@PathVariable UUID id) {
        return ResponseEntity.ok(inventarioService.obtenerLote(id));
    }

    @GetMapping("/{id}/prendas")
    public List<PrendaResponse> listarPrendas(@PathVariable UUID id) {
        return inventarioService.listarPrendas(id);
    }

    @PostMapping("/{id}/prendas")
    public ResponseEntity<PrendaResponse> agregarPrenda(
            @PathVariable UUID id,
            @Valid @RequestBody PrendaRequest req) {
        var prenda = inventarioService.agregarPrenda(id, req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PrendaResponse.from(prenda));
    }
}
