package com.tiendaropa.web.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record PedidoRequest(
    @NotBlank String whatsapp,
    @NotBlank String nombreCliente,
    @NotBlank String ciudad,
    String direccion,
    @NotNull UUID prendaId
) {}
