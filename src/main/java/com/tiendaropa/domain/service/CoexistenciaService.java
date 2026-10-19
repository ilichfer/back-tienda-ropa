package com.tiendaropa.domain.service;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Coexistencia de WhatsApp: el mismo número funciona a la vez en la app WhatsApp Business del
 * celular y en la Cloud API. Meta manda tres webhooks propios de este modo:
 *
 * - history: los chats 1:1 de los últimos 6 meses, al conectar el número.
 * - smb_app_state_sync: los contactos guardados en el celular (altas, cambios y bajas).
 * - smb_message_echoes: lo que alguien del equipo responde desde el celular.
 *
 * Nada de esto pasa por el bot: son mensajes que ya ocurrieron o que respondió una persona.
 */
public interface CoexistenciaService {

    /** Guarda en el historial del panel un bloque del webhook "history". */
    void procesarHistorial(JsonNode value);

    /** Crea o actualiza clientes a partir del webhook "smb_app_state_sync". */
    void procesarContactos(JsonNode value);

    /**
     * Guarda un mensaje que el equipo mandó desde la app del celular (un elemento de
     * message_echoes) y devuelve el número del cliente al que se le respondió, o null si el eco
     * no se pudo registrar.
     */
    String registrarEco(JsonNode eco);

    /**
     * Le pide a Meta que empiece a mandar los contactos ("smb_app_state_sync") o el historial
     * ("history"). Hay que hacerlo dentro de las 24 h siguientes a conectar el número.
     */
    JsonNode solicitarSincronizacion(String syncType);
}
