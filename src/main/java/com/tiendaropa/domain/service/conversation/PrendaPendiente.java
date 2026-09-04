package com.tiendaropa.domain.service.conversation;

/**
 * Una prenda que el cliente ya mandó (foto) o mencionó (sin foto) mientras se estaba
 * registrando otra: queda en cola en {@link ConversacionCliente#prendasPendientes} en vez de
 * perderse, y se procesa una por una preguntando su precio en orden.
 */
public class PrendaPendiente {
    public String mediaId;   // null si el cliente no tiene foto de esta prenda
    public String mimeType;
    public String descripcionSinFoto; // solo cuando mediaId es null: lo que el cliente escribió

    // wa_message_id de la foto (distinto de mediaId, que identifica el archivo). Se usa para
    // citarla en el mensaje "Sigamos con la siguiente prenda..." y que quede claro a cuál
    // foto se refiere cuando el cliente mandó varias seguidas.
    public String waMessageId;
}
