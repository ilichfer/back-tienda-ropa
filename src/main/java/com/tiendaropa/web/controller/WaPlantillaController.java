package com.tiendaropa.web.controller;

import com.tiendaropa.domain.model.WaPlantilla;
import com.tiendaropa.domain.repository.WaPlantillaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// Respuestas rápidas para el compositor del panel de WhatsApp (botón "⚡"): texto libre y
// editable, pensado para el chat 1:1 dentro de la ventana de 24h. No confundir con las
// plantillas de Meta (WaPlantillaMetaController), que son un concepto distinto: deben estar
// aprobadas por Meta y sirven para envíos masivos fuera de la ventana de 24h.
@RestController
@RequestMapping("/api/wa-plantillas")
@RequiredArgsConstructor
public class WaPlantillaController {

    private final WaPlantillaRepository repo;

    @GetMapping
    public List<WaPlantilla> listar(@RequestParam(required = false) Boolean activa) {
        if (Boolean.TRUE.equals(activa)) return repo.findByActivaTrueOrderByTitulo();
        return repo.findAllByOrderByTitulo();
    }

    @PostMapping
    public ResponseEntity<WaPlantilla> crear(@RequestBody WaPlantilla body) {
        body.setId(null);
        if (body.getActiva() == null) body.setActiva(true);
        return ResponseEntity.status(HttpStatus.CREATED).body(repo.save(body));
    }

    @PutMapping("/{id}")
    public WaPlantilla actualizar(@PathVariable UUID id, @RequestBody WaPlantilla body) {
        var existente = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Plantilla no encontrada: " + id));
        existente.setSlug(body.getSlug());
        existente.setTitulo(body.getTitulo());
        existente.setCuerpo(body.getCuerpo());
        if (body.getActiva() != null) existente.setActiva(body.getActiva());
        return repo.save(existente);
    }

    @PatchMapping("/{id}/activa")
    public WaPlantilla cambiarActiva(@PathVariable UUID id, @RequestBody Map<String, Boolean> body) {
        var existente = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Plantilla no encontrada: " + id));
        existente.setActiva(Boolean.TRUE.equals(body.get("activa")));
        return repo.save(existente);
    }

    @DeleteMapping("/{id}")
    public void borrar(@PathVariable UUID id) {
        repo.deleteById(id);
    }
}
