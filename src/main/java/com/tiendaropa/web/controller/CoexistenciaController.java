package com.tiendaropa.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaropa.domain.service.CoexistenciaService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Se usa una sola vez, justo después de conectar el número del celular en modo coexistencia:
 * Meta solo manda los contactos y el historial si se le piden, y da 24 horas para hacerlo.
 * Primero contactos, después historial:
 *
 *   POST /api/whatsapp/coexistencia/sincronizar?tipo=contactos
 *   POST /api/whatsapp/coexistencia/sincronizar?tipo=historial
 *
 * La respuesta solo confirma que Meta recibió el pedido; los datos llegan después por webhook.
 */
@RestController
@RequestMapping("/api/whatsapp/coexistencia")
@RequiredArgsConstructor
public class CoexistenciaController {

    private static final Map<String, String> TIPOS = Map.of(
            "contactos", "smb_app_state_sync",
            "historial", "history");

    private final CoexistenciaService coexistencia;

    @PostMapping("/sincronizar")
    public JsonNode sincronizar(@RequestParam String tipo) {
        var syncType = TIPOS.get(tipo);
        if (syncType == null) {
            throw new IllegalArgumentException("tipo debe ser 'contactos' o 'historial'");
        }
        return coexistencia.solicitarSincronizacion(syncType);
    }
}
