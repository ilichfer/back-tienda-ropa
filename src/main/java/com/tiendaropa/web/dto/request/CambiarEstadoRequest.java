package com.tiendaropa.web.dto.request;

import com.tiendaropa.domain.model.enums.EstadoPedido;
import jakarta.validation.constraints.NotNull;

public record CambiarEstadoRequest(
    @NotNull EstadoPedido estado,
    String nota
) {}
