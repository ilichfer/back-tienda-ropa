package com.tiendaropa.web.dto.response;

import com.tiendaropa.domain.model.Cliente;
import com.tiendaropa.domain.model.Inconveniente;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record InconvenienteResponse(
    UUID id,
    String whatsapp,
    String clienteNombre,
    String tipo,
    String descripcion,
    String estado,
    String pedidoId,
    List<String> fotos,
    String notasInternas,
    Instant createdAt
) {
    public static InconvenienteResponse from(Inconveniente i) {
        List<String> fotosList = null;
        if (i.getFotos() != null && !i.getFotos().isBlank()) {
            fotosList = List.of(i.getFotos().split("\\|"));
        }

        String nombre = null;
        Cliente c = i.getCliente();
        if (c != null && c.getNombre() != null) {
            nombre = c.getNombre();
        }

        return new InconvenienteResponse(
            i.getId(), i.getWhatsapp(), nombre,
            i.getTipo(), i.getDescripcion(), i.getEstado(),
            i.getPedidoId(), fotosList, i.getNotasInternas(),
            i.getCreatedAt()
        );
    }
}
