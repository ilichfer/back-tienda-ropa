package com.tiendaropa.domain.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public interface WhatsAppService {

    void procesarWebhook(JsonNode payload);

    void enviarMensaje(String destinatario, String texto);

    // Igual que enviarMensaje, pero citando otro mensaje (la acción de WhatsApp Web de
    // "responder" con la vista previa del mensaje original arriba). replyToWaMessageId es el
    // wa_message_id del mensaje citado; si es null o vacío, se comporta igual que el método de
    // 2 parámetros (mensaje normal sin cita).
    void enviarMensaje(String destinatario, String texto, String replyToWaMessageId);

    void enviarBotones(String destinatario, String texto, List<Map<String, String>> botones);

    void enviarNotificacionEnvio(String destinatario, String nombre, String guia);

    void enviarConfirmacionApartado(String destinatario, String nombre, String prenda, String precio);

    void actualizarNombreCliente(String whatsappFrom, String nombre);

    void borrarConversacion(String whatsappFrom);
}
