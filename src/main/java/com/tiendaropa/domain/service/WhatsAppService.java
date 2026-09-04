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

    // Igual que enviarBotones, pero citando otro mensaje (ver enviarMensaje con
    // replyToWaMessageId) — para cuando hay varias fotos encoladas y hace falta dejar claro
    // a cuál de ellas se refiere el mensaje. Si replyToWaMessageId es null o vacío, se
    // comporta igual que el método de 3 parámetros.
    void enviarBotones(String destinatario, String texto, List<Map<String, String>> botones, String replyToWaMessageId);

    // Manda el formulario nativo de WhatsApp (Flow) para pedir los datos de envío en una sola
    // pantalla, en vez del ir y venir de preguntas de texto. Requiere que el Flow ya esté creado
    // y publicado en WhatsApp Manager, y su ID configurado en whatsapp.flow-envio-id.
    void enviarFlujoEnvio(String destinatario);

    void enviarNotificacionEnvio(String destinatario, String nombre, String guia);

    // Manda cualquier plantilla ya aprobada por Meta (WhatsApp Manager), con sus variables
    // {{1}}, {{2}}... en orden — generaliza el patrón de enviarNotificacionEnvio para poder
    // usar cualquier plantilla registrada en wa_plantillas_meta, no solo "notificacion_envio".
    // Devuelve el wa_message_id del mensaje enviado (o lanza si Meta lo rechaza, ej. plantilla
    // no aprobada o destinatario fuera de la ventana de 24h sin plantilla válida).
    String enviarPlantillaMeta(String destinatario, String nombrePlantilla, String idioma, List<String> valoresVariables);

    // Manda una imagen a un número (subiéndola primero a Meta) — no existía ninguna forma de
    // mandar media hacia afuera, solo texto/botones/plantilla. Devuelve el wa_message_id.
    String enviarImagen(String destinatario, byte[] bytes, String mimeType, String caption);

    // Reenvía la imagen de un mensaje ya recibido (mensajeId, propio de nuestra BD) a otro
    // número — resuelve los bytes (local si ya la tenemos guardada, si no la descarga de Meta)
    // y llama a enviarImagen.
    void reenviarImagen(java.util.UUID mensajeId, String destinatario);

    // Mensaje fijo que se manda al presionar "Marcar como enviado" desde el panel, cuando
    // todavía no se conoce el número de guía (avisa que el pedido salió y que la guía se
    // envía después). No depende de IA: es siempre el mismo texto.
    void enviarAvisoEnviadoSinGuia(String destinatario, String nombre);

    void enviarConfirmacionApartado(String destinatario, String nombre, String prenda, String precio);

    void actualizarNombreCliente(String whatsappFrom, String nombre);

    void borrarConversacion(String whatsappFrom);
}
