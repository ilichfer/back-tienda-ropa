package com.tiendaropa.domain.service;

import com.tiendaropa.domain.model.Lote;
import com.tiendaropa.domain.model.Prenda;
import com.tiendaropa.web.dto.request.LoteRequest;
import com.tiendaropa.web.dto.request.PrendaRequest;
import com.tiendaropa.web.dto.response.LoteResponse;
import com.tiendaropa.web.dto.response.PrendaResponse;
import java.util.List;
import java.util.UUID;

public interface InventarioService {

    Lote crearLote(LoteRequest req);

    List<LoteResponse> listarLotes(Boolean soloActivos);

    Prenda agregarPrenda(UUID loteId, PrendaRequest req);

    List<PrendaResponse> listarPrendas(UUID loteId);

    LoteResponse obtenerLote(UUID loteId);
}
