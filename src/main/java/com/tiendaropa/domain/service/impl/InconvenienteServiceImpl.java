package com.tiendaropa.domain.service.impl;

import com.tiendaropa.domain.model.Inconveniente;
import com.tiendaropa.domain.repository.ClienteRepository;
import com.tiendaropa.domain.repository.InconvenienteRepository;
import com.tiendaropa.domain.service.InconvenienteService;
import com.tiendaropa.web.dto.response.InconvenienteResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class InconvenienteServiceImpl implements InconvenienteService {

    private final InconvenienteRepository repo;
    private final ClienteRepository clienteRepo;

    @Override
    @Transactional(readOnly = true)
    public List<InconvenienteResponse> listar(String estado) {
        List<Inconveniente> list;
        if (estado == null || estado.isBlank())
            list = repo.findAllByOrderByCreatedAtDesc();
        else
            list = repo.findByEstadoOrderByCreatedAtDesc(estado);
        return list.stream().map(InconvenienteResponse::from).toList();
    }

    @Override
    @Transactional
    public InconvenienteResponse cambiarEstado(UUID id, String nuevoEstado) {
        var inc = repo.findById(id)
            .orElseThrow(() -> new RuntimeException("Inconveniente no encontrado"));
        inc.setEstado(nuevoEstado);
        return InconvenienteResponse.from(repo.save(inc));
    }

    @Override
    @Transactional
    public InconvenienteResponse agregarNotas(UUID id, String notas) {
        var inc = repo.findById(id)
            .orElseThrow(() -> new RuntimeException("Inconveniente no encontrado"));
        inc.setNotasInternas(notas);
        return InconvenienteResponse.from(repo.save(inc));
    }

    @Transactional
    public Inconveniente crear(String whatsapp, String tipo, String descripcion,
                               String pedidoId, List<String> fotos) {
        var cliente = clienteRepo.findByWhatsapp(whatsapp).orElse(null);
        var fotosStr = fotos != null && !fotos.isEmpty()
            ? String.join("|", fotos) : null;

        var inc = Inconveniente.builder()
            .whatsapp(whatsapp)
            .cliente(cliente)
            .tipo(tipo)
            .descripcion(descripcion)
            .pedidoId(pedidoId)
            .fotos(fotosStr)
            .estado("RECIBIDO")
            .build();

        inc = repo.save(inc);
        log.info("Inconveniente creado {} tipo={} fotos={}", inc.getId(), tipo,
            fotos != null ? fotos.size() : 0);
        return inc;
    }
}
