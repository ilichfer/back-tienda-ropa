package com.tiendaropa.web.dto.response;

import com.tiendaropa.domain.model.SolicitudEnvio;
import java.time.Instant;
import java.util.UUID;

public record EnvioResponse(
    UUID id,
    String whatsapp,
    String nombreCompleto,
    String telefono,
    String cedula,
    String direccion,
    String ciudad,
    String barrio,
    String notas,
    String estado,
    Instant createdAt
) {
    public static EnvioResponse from(SolicitudEnvio e) {
        return new EnvioResponse(
            e.getId(), e.getWhatsapp(),
            e.getNombreCompleto(), e.getTelefono(), e.getCedula(),
            e.getDireccion(), e.getCiudad(), e.getBarrio(),
            e.getNotas(), e.getEstado(), e.getCreatedAt()
        );
    }
}
