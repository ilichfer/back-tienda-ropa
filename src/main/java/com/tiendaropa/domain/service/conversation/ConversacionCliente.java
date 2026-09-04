package com.tiendaropa.domain.service.conversation;

import java.util.List;

/**
 * Estado en curso del flujo con el bot (ENVIO / PEDIDO / INCONVENIENTE) para un cliente.
 * Se serializa completo (campos sin getters incluidos, ver {@link ConversationStateStore}) en
 * la tabla wa_conversaciones para sobrevivir a un reinicio del backend sin perder lo que el
 * cliente ya escribió.
 */
public class ConversacionCliente {
    public String flujo;
    public int paso;
    public String nombre;
    public String telefono;
    public String cedula;
    public String direccion;
    public String ciudad;
    public String barrio;
    public String concepto;
    public String mediaId;
    public String mediaPath;
    public String mimeType;
    public boolean soportePago;
    public String descripcion;
    public List<String> fotosInconveniente;
    public List<PrendaPendiente> prendasPendientes;
}
