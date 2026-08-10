package com.tiendaropa.web.dto.response;

import com.tiendaropa.domain.model.CuentaMovimiento;
import java.time.Instant;
import java.util.UUID;

public record CuentaMovimientoResponse(
    UUID id,
    String tipo,
    String concepto,
    Long valor,
    String estado,
    String referencia,
    String metodo,
    String mediaId,
    String mediaPath,
    String mimeType,
    Instant createdAt
) {
    public static CuentaMovimientoResponse from(CuentaMovimiento m) {
        return new CuentaMovimientoResponse(
            m.getId(), m.getTipo(), m.getConcepto(), m.getValor(), m.getEstado(),
            m.getReferencia(), m.getMetodo(),
            m.getMediaId(), m.getMediaPath(), m.getMimeType(),
            m.getCreatedAt()
        );
    }
}
