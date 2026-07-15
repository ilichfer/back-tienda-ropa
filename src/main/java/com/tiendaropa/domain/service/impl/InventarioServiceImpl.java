package com.tiendaropa.domain.service.impl;

import com.tiendaropa.domain.model.Lote;
import com.tiendaropa.domain.model.Prenda;
import com.tiendaropa.domain.model.enums.EstadoPrenda;
import com.tiendaropa.domain.repository.LoteRepository;
import com.tiendaropa.domain.repository.PrendaRepository;
import com.tiendaropa.domain.service.InventarioService;
import com.tiendaropa.web.dto.request.LoteRequest;
import com.tiendaropa.web.dto.request.PrendaRequest;
import com.tiendaropa.web.dto.response.LoteResponse;
import com.tiendaropa.web.dto.response.PrendaResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InventarioServiceImpl implements InventarioService {

    private final LoteRepository   loteRepo;
    private final PrendaRepository prendaRepo;

    @Override
    @Transactional
    public Lote crearLote(LoteRequest req) {
        return loteRepo.save(Lote.builder()
                .nombre(req.nombre())
                .fechaLive(req.fechaLive())
                .descripcion(req.descripcion())
                .build());
    }

    @Override
    public List<LoteResponse> listarLotes(Boolean soloActivos) {
        var lotes = Boolean.TRUE.equals(soloActivos)
                ? loteRepo.findByActivoTrueOrderByCreatedAtDesc()
                : loteRepo.findAllByOrderByCreatedAtDesc();

        return lotes.stream().map(l -> {
            int total = (int) prendaRepo.countByLoteId(l.getId());
            int disponibles = (int) prendaRepo.countByLoteIdAndEstado(l.getId(), EstadoPrenda.DISPONIBLE);
            return LoteResponse.from(l, total, disponibles);
        }).toList();
    }

    @Override
    @Transactional
    public Prenda agregarPrenda(UUID loteId, PrendaRequest req) {
        var lote = loteRepo.findById(loteId)
                .orElseThrow(() -> new IllegalArgumentException("Lote no encontrado"));

        return prendaRepo.save(Prenda.builder()
                .lote(lote)
                .nombre(req.nombre())
                .talla(req.talla())
                .color(req.color())
                .precio(req.precio())
                .fotoUrl(req.fotoUrl())
                .build());
    }

    @Override
    public List<PrendaResponse> listarPrendas(UUID loteId) {
        return prendaRepo.findByLoteIdOrderByCreatedAtAsc(loteId)
                .stream()
                .map(PrendaResponse::from)
                .toList();
    }

    @Override
    public LoteResponse obtenerLote(UUID loteId) {
        var lote = loteRepo.findById(loteId)
                .orElseThrow(() -> new IllegalArgumentException("Lote no encontrado"));

        var totalPrendas = prendaRepo.findByLoteIdOrderByCreatedAtAsc(loteId).size();
        var disponibles = (int) prendaRepo.countByLoteIdAndEstado(loteId, EstadoPrenda.DISPONIBLE);

        return LoteResponse.from(lote, totalPrendas, disponibles);
    }
}
