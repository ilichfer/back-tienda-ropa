package com.tiendaropa.web.dto.response;

import com.tiendaropa.domain.model.Pedido;
import com.tiendaropa.domain.model.enums.EstadoPedido;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PedidoResponse(
    UUID id,
    Long numero,
    EstadoPedido estado,
    ClienteInfo cliente,
    PrendaInfo prenda,
    BigDecimal precioFinal,
    BigDecimal costoEnvio,
    BigDecimal total,
    String numeroGuia,
    String transportadora,
    String notas,
    Instant createdAt
) {
    public static PedidoResponse from(Pedido p) {
        var c = p.getCliente();
        var pr = p.getPrenda();
        var l = pr.getLote();
        return new PedidoResponse(
            p.getId(), p.getNumero(), p.getEstado(),
            new ClienteInfo(c.getNombre(), c.getWhatsapp(), c.getCiudad()),
            new PrendaInfo(pr.getNombre(), pr.getTalla(), pr.getPrecio(), new LoteInfo(l.getNombre())),
            p.getPrecioFinal(), p.getCostoEnvio(),
            p.getPrecioFinal().add(p.getCostoEnvio()),
            p.getNumeroGuia(), p.getTransportadora(), p.getNotas(),
            p.getCreatedAt()
        );
    }

    public record ClienteInfo(String nombre, String whatsapp, String ciudad) {}
    public record PrendaInfo(String nombre, String talla, BigDecimal precio, LoteInfo lote) {}
    public record LoteInfo(String nombre) {}
}
