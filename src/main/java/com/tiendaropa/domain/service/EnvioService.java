package com.tiendaropa.domain.service;

import com.tiendaropa.domain.model.SolicitudEnvio;
import com.tiendaropa.web.dto.response.EnvioResponse;

import java.util.List;
import java.util.UUID;

public interface EnvioService {

    List<EnvioResponse> listar(String estado);

    EnvioResponse cambiarEstado(UUID id, String nuevoEstado);

    SolicitudEnvio crearDesdeTexto(String whatsapp, String textoCrudo);

    SolicitudEnvio crearConDatos(String whatsapp, String nombre, String telefono,
                                  String cedula, String direccion, String ciudad, String barrio);
}
