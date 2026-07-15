package com.tiendaropa.web.dto.response;

import com.tiendaropa.domain.model.Prenda;
import com.tiendaropa.domain.model.enums.EstadoPrenda;
import java.math.BigDecimal;
import java.util.UUID;

public record PrendaResponse(
    UUID id,
    String nombre,
    String talla,
    String color,
    BigDecimal precio,
    EstadoPrenda estado,
    String fotoUrl
) {
    public static PrendaResponse from(Prenda p) {
        return new PrendaResponse(
            p.getId(), p.getNombre(), p.getTalla(),
            p.getColor(), p.getPrecio(), p.getEstado(), p.getFotoUrl()
        );
    }
}
