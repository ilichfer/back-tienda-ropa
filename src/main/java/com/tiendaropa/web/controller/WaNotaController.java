package com.tiendaropa.web.controller;

import com.tiendaropa.domain.model.WaNota;
import com.tiendaropa.domain.repository.WaNotaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/wa-notas")
@RequiredArgsConstructor
public class WaNotaController {

    private final WaNotaRepository repo;

    @GetMapping
    public List<WaNota> listar(@RequestParam String whatsappFrom) {
        return repo.findByWhatsappFromOrderByCreatedAtDesc(whatsappFrom);
    }

    @PostMapping
    public ResponseEntity<WaNota> crear(@RequestBody WaNota body) {
        if (body.getWhatsappFrom() == null || body.getWhatsappFrom().isBlank())
            throw new IllegalArgumentException("'whatsappFrom' es requerido");
        if (body.getContenido() == null || body.getContenido().isBlank())
            throw new IllegalArgumentException("'contenido' es requerido");
        body.setId(null);
        return ResponseEntity.status(HttpStatus.CREATED).body(repo.save(body));
    }

    @DeleteMapping("/{id}")
    public void borrar(@PathVariable UUID id) {
        repo.deleteById(id);
    }
}
