package com.tiendaropa.domain.service.impl;

import com.tiendaropa.domain.model.Cliente;
import com.tiendaropa.domain.model.Cuenta;
import com.tiendaropa.domain.model.CuentaMovimiento;
import com.tiendaropa.domain.repository.ClienteRepository;
import com.tiendaropa.domain.repository.CuentaMovimientoRepository;
import com.tiendaropa.domain.repository.CuentaRepository;
import com.tiendaropa.domain.service.CuentaService;
import com.tiendaropa.web.dto.response.CuentaMovimientoResponse;
import com.tiendaropa.web.dto.response.CuentaResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CuentaServiceImpl implements CuentaService {

    private final CuentaRepository cuentaRepo;
    private final CuentaMovimientoRepository movRepo;
    private final ClienteRepository clienteRepo;

    private static final long MINUTOS_PARA_ANULAR = 30;

    @Override
    @Transactional(readOnly = true)
    public List<CuentaResponse> listar() {
        return cuentaRepo.findAll().stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional
    public CuentaResponse crearCuenta(String whatsapp) {
        return toResponse(getOrCreateCuenta(whatsapp));
    }

    @Override
    @Transactional
    public CuentaMovimientoResponse registrarCargo(String whatsapp, String concepto, Long valor,
                                                   String mediaId, String mediaPath, String mimeType) {
        var cuenta = getOrCreateCuenta(whatsapp);
        var mov = movRepo.save(CuentaMovimiento.builder()
                .cuenta(cuenta)
                .tipo("CARGO")
                .concepto(concepto)
                .valor(valor)
                .estado(valor == null ? "VALOR_POR_DEFINIR" : "CONFIRMADO")
                .mediaId(mediaId)
                .mediaPath(mediaPath)
                .mimeType(mimeType)
                .build());
        log.info("CARGO registrado en cuenta {} ({}): {} {}", cuenta.getId(), whatsapp,
                concepto, valor == null ? "sin valor" : "$" + valor);
        return CuentaMovimientoResponse.from(mov);
    }

    @Override
    @Transactional
    public CuentaMovimientoResponse registrarAbonoDesdeBot(String whatsapp, Long valor,
                                                           String mediaId, String mediaPath, String mimeType) {
        var cuenta = getOrCreateCuenta(whatsapp);
        var mov = movRepo.save(CuentaMovimiento.builder()
                .cuenta(cuenta)
                .tipo("ABONO")
                .concepto("Soporte de pago")
                .valor(valor)
                .estado("PENDIENTE_VALIDAR")
                .mediaId(mediaId)
                .mediaPath(mediaPath)
                .mimeType(mimeType)
                .build());
        log.info("ABONO por soporte de pago en cuenta {} ({}): ${}", cuenta.getId(), whatsapp, valor);
        return CuentaMovimientoResponse.from(mov);
    }

    @Override
    @Transactional
    public CuentaMovimientoResponse registrarAbono(UUID cuentaId, Long valor, String referencia, String metodo) {
        var cuenta = cuentaRepo.findById(cuentaId)
                .orElseThrow(() -> new IllegalArgumentException("Cuenta no encontrada"));
        var mov = movRepo.save(CuentaMovimiento.builder()
                .cuenta(cuenta)
                .tipo("ABONO")
                .valor(valor)
                .referencia(referencia)
                .metodo(metodo)
                .estado("PENDIENTE_VALIDAR")
                .build());
        log.info("ABONO pendiente en cuenta {}: ${} ({})", cuentaId, valor, metodo);
        return CuentaMovimientoResponse.from(mov);
    }

    @Override
    @Transactional
    public CuentaMovimientoResponse completarValor(UUID movimientoId, Long valor) {
        var mov = movRepo.findById(movimientoId)
                .orElseThrow(() -> new IllegalArgumentException("Movimiento no encontrado"));
        // Los abonos también: el bot ya no le pregunta al cliente cuánto pagó al recibir la
        // foto del comprobante, así que el valor lo completa el operador desde el panel.
        mov.setValor(valor);
        if ("VALOR_POR_DEFINIR".equals(mov.getEstado())) {
            mov.setEstado("CONFIRMADO");
        }
        movRepo.save(mov);
        log.info("Valor completado en cargo {}: ${}", movimientoId, valor);
        return CuentaMovimientoResponse.from(mov);
    }

    @Override
    @Transactional
    public CuentaMovimientoResponse actualizarConcepto(UUID movimientoId, String concepto) {
        var mov = movRepo.findById(movimientoId)
                .orElseThrow(() -> new IllegalArgumentException("Movimiento no encontrado"));
        mov.setConcepto(concepto);
        movRepo.save(mov);
        return CuentaMovimientoResponse.from(mov);
    }

    @Override
    @Transactional
    public CuentaMovimientoResponse adjuntarFoto(UUID movimientoId, String mediaId, String mediaPath, String mimeType) {
        var mov = movRepo.findById(movimientoId)
                .orElseThrow(() -> new IllegalArgumentException("Movimiento no encontrado"));
        mov.setMediaId(mediaId);
        mov.setMediaPath(mediaPath);
        mov.setMimeType(mimeType);
        movRepo.save(mov);
        return CuentaMovimientoResponse.from(mov);
    }

    @Override
    @Transactional
    public CuentaMovimientoResponse validarAbono(UUID movimientoId) {
        var mov = movRepo.findById(movimientoId)
                .orElseThrow(() -> new IllegalArgumentException("Movimiento no encontrado"));
        if (!"ABONO".equals(mov.getTipo())) {
            throw new IllegalArgumentException("Solo los abonos se validan");
        }
        // Un abono sin valor (comprobante recibido por WhatsApp que nadie ha revisado aún) no
        // se puede confirmar: sumaría $0 al saldo sin que nadie lo note.
        if (mov.getValor() == null) {
            throw new IllegalArgumentException("Primero registra el valor del pago");
        }
        mov.setEstado("CONFIRMADO");
        movRepo.save(mov);
        log.info("ABONO validado: {}", movimientoId);
        return CuentaMovimientoResponse.from(mov);
    }

    @Override
    @Transactional
    public CuentaMovimientoResponse rechazarAbono(UUID movimientoId) {
        var mov = movRepo.findById(movimientoId)
                .orElseThrow(() -> new IllegalArgumentException("Movimiento no encontrado"));
        mov.setEstado("RECHAZADO");
        movRepo.save(mov);
        log.info("ABONO rechazado: {}", movimientoId);
        return CuentaMovimientoResponse.from(mov);
    }

    @Override
    @Transactional
    public Optional<CuentaMovimientoResponse> anularDesdeBot(UUID movimientoId, String whatsapp) {
        var mov = movRepo.findById(movimientoId).orElse(null);
        if (mov == null) return Optional.empty();
        if (!whatsapp.equals(mov.getCuenta().getCliente().getWhatsapp())) {
            log.warn("Intento de anular movimiento {} desde {}, que no es el dueño de la cuenta", movimientoId, whatsapp);
            return Optional.empty();
        }
        // Solo se puede deshacer lo que el operador todavía no tocó: un cargo sin valor o un
        // abono sin validar. Si ya lo revisaron desde el panel, la corrección la hace un humano.
        var sinRevisar = ("CARGO".equals(mov.getTipo()) && "VALOR_POR_DEFINIR".equals(mov.getEstado()))
                || ("ABONO".equals(mov.getTipo()) && "PENDIENTE_VALIDAR".equals(mov.getEstado()) && mov.getValor() == null);
        var reciente = mov.getCreatedAt() == null
                || mov.getCreatedAt().isAfter(Instant.now().minus(MINUTOS_PARA_ANULAR, ChronoUnit.MINUTES));
        if (!sinRevisar || !reciente) {
            log.info("Movimiento {} no se anula desde el bot (estado={}, creado={})", movimientoId, mov.getEstado(), mov.getCreatedAt());
            return Optional.empty();
        }
        var resp = CuentaMovimientoResponse.from(mov);
        movRepo.delete(mov);
        log.info("{} {} anulado por el cliente {} desde el bot", mov.getTipo(), movimientoId, whatsapp);
        return Optional.of(resp);
    }

    private Cuenta getOrCreateCuenta(String whatsapp) {
        var cuenta = cuentaRepo.findByCliente_Whatsapp(whatsapp).orElse(null);
        if (cuenta != null) return cuenta;

        var cliente = clienteRepo.findByWhatsapp(whatsapp).orElseGet(() ->
                clienteRepo.save(Cliente.builder().whatsapp(whatsapp).build()));
        return cuentaRepo.save(Cuenta.builder().cliente(cliente).build());
    }

    private CuentaResponse toResponse(Cuenta c) {
        long cargos = movRepo.sumCargos(c.getId());
        long abonos = movRepo.sumAbonos(c.getId());
        long saldo = cargos - abonos;
        long pendValor = movRepo.countCargosSinValor(c.getId());
        long pendAbonos = movRepo.countAbonosPorValidar(c.getId());
        var movs = movRepo.findByCuentaIdOrderByCreatedAtDesc(c.getId())
                .stream().map(CuentaMovimientoResponse::from).toList();
        return CuentaResponse.from(c, saldo, cargos, abonos, pendValor, pendAbonos, movs);
    }
}
