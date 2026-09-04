package com.tiendaropa.web.controller;

import com.tiendaropa.domain.model.WaPlantillaMeta;
import com.tiendaropa.domain.repository.WaPlantillaMetaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

// Registro de plantillas YA aprobadas por Meta en WhatsApp Manager, para poder usarlas en
// envíos masivos (BroadcastController). Esta app no crea ni aprueba plantillas ante Meta.
@RestController
@RequestMapping("/api/wa-plantillas-meta")
@RequiredArgsConstructor
public class WaPlantillaMetaController {

    private final WaPlantillaMetaRepository repo;

    @GetMapping
    public List<WaPlantillaMeta> listar(@RequestParam(required = false) Boolean activa) {
        if (Boolean.TRUE.equals(activa)) return repo.findByActivaTrueOrderByNombre();
        return repo.findAllByOrderByNombre();
    }

    @PostMapping
    public ResponseEntity<WaPlantillaMeta> crear(@RequestBody WaPlantillaMeta body) {
        if (body.getNombre() == null || body.getNombre().isBlank())
            throw new IllegalArgumentException("'nombre' es requerido y debe coincidir exacto con el de WhatsApp Manager");
        body.setId(null);
        if (body.getActiva() == null) body.setActiva(true);
        if (body.getIdioma() == null || body.getIdioma().isBlank()) body.setIdioma("es");
        return ResponseEntity.status(HttpStatus.CREATED).body(repo.save(body));
    }

    @PutMapping("/{id}")
    public WaPlantillaMeta actualizar(@PathVariable UUID id, @RequestBody WaPlantillaMeta body) {
        var existente = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Plantilla de Meta no encontrada: " + id));
        existente.setNombre(body.getNombre());
        existente.setIdioma(body.getIdioma());
        existente.setVariables(body.getVariables());
        existente.setDescripcion(body.getDescripcion());
        if (body.getActiva() != null) existente.setActiva(body.getActiva());
        return repo.save(existente);
    }

    @DeleteMapping("/{id}")
    public void borrar(@PathVariable UUID id) {
        repo.deleteById(id);
    }
}
