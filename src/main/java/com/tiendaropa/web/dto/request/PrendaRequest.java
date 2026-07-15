package com.tiendaropa.web.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record PrendaRequest(
    @NotBlank String nombre,
    String talla,
    String color,
    @NotNull BigDecimal precio,
    String fotoUrl
) {}
