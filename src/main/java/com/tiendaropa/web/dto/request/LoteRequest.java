package com.tiendaropa.web.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record LoteRequest(
    @NotBlank String nombre,
    @NotNull LocalDate fechaLive,
    String descripcion
) {}
