package com.tiendaropa.domain.service;

import com.tiendaropa.domain.model.Inconveniente;
import com.tiendaropa.web.dto.response.InconvenienteResponse;

import java.util.List;
import java.util.UUID;

public interface InconvenienteService {

    List<InconvenienteResponse> listar(String estado);

    InconvenienteResponse cambiarEstado(UUID id, String nuevoEstado);

    InconvenienteResponse agregarNotas(UUID id, String notas);

    Inconveniente crear(String whatsapp, String tipo, String descripcion,
                        String pedidoId, List<String> fotos);
}
