package com.tiendaropa.domain.service.impl;

import com.tiendaropa.domain.model.WaBroadcast;
import com.tiendaropa.domain.repository.WaBroadcastRepository;
import com.tiendaropa.domain.repository.WaPlantillaMetaRepository;
import com.tiendaropa.domain.service.BroadcastService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BroadcastServiceImpl implements BroadcastService {

    private final WaPlantillaMetaRepository plantillaMetaRepo;
    private final WaBroadcastRepository broadcastRepo;
    private final BroadcastRunner runner;

    @Override
    public WaBroadcast crearYEjecutar(UUID plantillaMetaId, Map<String, String> variablesConfig, List<String> destinatarios) {
        var plantilla = plantillaMetaRepo.findById(plantillaMetaId)
                .orElseThrow(() -> new IllegalArgumentException("Plantilla de Meta no encontrada: " + plantillaMetaId));

        var destinatariosLimpios = destinatarios.stream().filter(d -> d != null && !d.isBlank()).distinct().toList();
        if (destinatariosLimpios.isEmpty())
            throw new IllegalArgumentException("Debes elegir al menos un destinatario");

        var broadcast = broadcastRepo.save(WaBroadcast.builder()
                .plantillaMetaId(plantillaMetaId)
                .variablesConfig(serializarConfig(variablesConfig))
                .total(destinatariosLimpios.size())
                .estado("PENDIENTE")
                .build());

        // Llamada externa al bean BroadcastRunner (no this.algo()) para que @Async sí aplique
        // — ver el comentario en BroadcastRunner sobre por qué no puede vivir en esta clase.
        runner.ejecutar(broadcast.getId(), plantilla.getNombre(), plantilla.getIdioma(),
                plantilla.getVariables(), variablesConfig, destinatariosLimpios);

        return broadcast;
    }

    private String serializarConfig(Map<String, String> variablesConfig) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(variablesConfig);
        } catch (Exception e) {
            return "{}";
        }
    }
}
