package com.tiendaropa.domain.service;

import com.tiendaropa.domain.model.WaBroadcast;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface BroadcastService {

    // Crea la campaña (estado PENDIENTE) y dispara el envío en segundo plano (@Async en la
    // implementación) — devuelve de inmediato para que el frontend haga polling del progreso.
    // variablesConfig: { "<etiqueta de la variable>": "<valor fijo>" | "{{cliente_nombre}}" }.
    // "{{cliente_nombre}}" es el único valor especial: se reemplaza por el nombre de cada
    // Cliente destinatario (o su número, si no tiene nombre guardado); cualquier otro valor
    // se manda tal cual, igual para todos los destinatarios.
    WaBroadcast crearYEjecutar(UUID plantillaMetaId, Map<String, String> variablesConfig, List<String> destinatarios);
}
