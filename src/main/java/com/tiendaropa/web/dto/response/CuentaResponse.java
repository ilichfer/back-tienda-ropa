package com.tiendaropa.web.dto.response;

import com.tiendaropa.domain.model.Cuenta;
import java.util.List;
import java.util.UUID;

public record CuentaResponse(
    UUID id,
    ClienteInfo cliente,
    long saldo,
    long totalCargos,
    long totalAbonos,
    long pendientesValor,
    long abonosPorValidar,
    List<CuentaMovimientoResponse> movimientos
) {
    public record ClienteInfo(UUID id, String whatsapp, String nombre, String ciudad) {}

    public static CuentaResponse from(Cuenta c, long saldo, long totalCargos, long totalAbonos,
                                      long pendientesValor, long abonosPorValidar,
                                      List<CuentaMovimientoResponse> movimientos) {
        var cl = c.getCliente();
        return new CuentaResponse(
            c.getId(),
            new ClienteInfo(cl.getId(), cl.getWhatsapp(), cl.getNombre(), cl.getCiudad()),
            saldo, totalCargos, totalAbonos, pendientesValor, abonosPorValidar, movimientos
        );
    }
}
