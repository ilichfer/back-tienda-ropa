package com.tiendaropa.web.dto.request;

import jakarta.validation.constraints.NotBlank;

public record EnvioEstadoRequest(
    @NotBlank String estado
) {}
