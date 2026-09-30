package com.tiendaropa.domain.service;

import com.tiendaropa.web.dto.response.CuentaMovimientoResponse;
import com.tiendaropa.web.dto.response.CuentaResponse;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CuentaService {

    List<CuentaResponse> listar();

    CuentaResponse crearCuenta(String whatsapp);

    CuentaMovimientoResponse registrarCargo(String whatsapp, String concepto, Long valor,
                                            String mediaId, String mediaPath, String mimeType);

    CuentaMovimientoResponse registrarAbonoDesdeBot(String whatsapp, Long valor,
                                                    String mediaId, String mediaPath, String mimeType);

    CuentaMovimientoResponse registrarAbono(UUID cuentaId, Long valor, String referencia, String metodo);

    CuentaMovimientoResponse completarValor(UUID movimientoId, Long valor);

    CuentaMovimientoResponse actualizarConcepto(UUID movimientoId, String concepto);

    CuentaMovimientoResponse adjuntarFoto(UUID movimientoId, String mediaId, String mediaPath, String mimeType);

    CuentaMovimientoResponse validarAbono(UUID movimientoId);

    CuentaMovimientoResponse rechazarAbono(UUID movimientoId);

    /**
     * Borra un cargo/abono que el cliente registró por error desde el bot (botón "Me
     * equivoqué"). Solo aplica si es de ese cliente, es reciente y el operador aún no lo revisó;
     * si no, devuelve vacío y no toca nada.
     */
    Optional<CuentaMovimientoResponse> anularDesdeBot(UUID movimientoId, String whatsapp);
}
