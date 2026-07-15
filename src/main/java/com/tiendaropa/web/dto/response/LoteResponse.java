package com.tiendaropa.web.dto.response;

import com.tiendaropa.domain.model.Lote;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LoteResponse(
    UUID id,
    String nombre,
    LocalDate fechaLive,
    String descripcion,
    Boolean activo,
    int totalPrendas,
    int prendasDisponibles,
    Instant createdAt
) {
    public static LoteResponse from(Lote l, int totalPrendas, int prendasDisponibles) {
        return new LoteResponse(
            l.getId(), l.getNombre(), l.getFechaLive(),
            l.getDescripcion(), l.getActivo(),
            totalPrendas, prendasDisponibles,
            l.getCreatedAt()
        );
    }
}
