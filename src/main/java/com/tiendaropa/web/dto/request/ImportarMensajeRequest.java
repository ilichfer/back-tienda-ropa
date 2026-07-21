package com.tiendaropa.web.dto.request;

import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ImportarMensajeRequest {
    private String whatsappFrom;
    private String nombre;
    private String contenido;
    private String tipo;
    private String direccion;
    private String mimeType;
    private String mediaPath;
    private Long timestamp;
}
