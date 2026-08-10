package com.tiendaropa.domain.repository;

import com.tiendaropa.domain.model.CuentaMovimiento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface CuentaMovimientoRepository extends JpaRepository<CuentaMovimiento, UUID> {

    List<CuentaMovimiento> findByCuentaIdOrderByCreatedAtDesc(UUID cuentaId);

    @Query("SELECT COALESCE(SUM(m.valor), 0) FROM CuentaMovimiento m WHERE m.cuenta.id = :cuentaId AND m.tipo = 'CARGO' AND m.valor IS NOT NULL")
    Long sumCargos(UUID cuentaId);

    @Query("SELECT COALESCE(SUM(m.valor), 0) FROM CuentaMovimiento m WHERE m.cuenta.id = :cuentaId AND m.tipo = 'ABONO' AND m.estado = 'CONFIRMADO'")
    Long sumAbonos(UUID cuentaId);

    @Query("SELECT COUNT(m) FROM CuentaMovimiento m WHERE m.cuenta.id = :cuentaId AND m.tipo = 'CARGO' AND m.valor IS NULL")
    long countCargosSinValor(UUID cuentaId);

    @Query("SELECT COUNT(m) FROM CuentaMovimiento m WHERE m.cuenta.id = :cuentaId AND m.tipo = 'ABONO' AND m.estado = 'PENDIENTE_VALIDAR'")
    long countAbonosPorValidar(UUID cuentaId);
}
