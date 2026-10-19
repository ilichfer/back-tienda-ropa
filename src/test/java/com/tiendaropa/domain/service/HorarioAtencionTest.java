package com.tiendaropa.domain.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class HorarioAtencionTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final String LUNES_A_SABADO = "MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY";

    private static HorarioAtencion a(int anio, int mes, int dia, int hora, int minuto) {
        var instante = ZonedDateTime.of(anio, mes, dia, hora, minuto, 0, 0, BOGOTA).toInstant();
        return new HorarioAtencion(LUNES_A_SABADO, "08:00", "18:00", "America/Bogota", Clock.fixed(instante, BOGOTA));
    }

    @Test
    void dentroDelHorarioSeAtiendeEnBreve() {
        var h = a(2026, 10, 19, 10, 0); // lunes 10:00
        assertThat(h.estaAbierto()).isTrue();
        assertThat(h.cuandoTeAtendemos()).isEqualTo("en breve");
    }

    @Test
    void enLaNocheSeAtiendeAlDiaSiguiente() {
        var h = a(2026, 10, 19, 21, 30); // lunes 21:30
        assertThat(h.estaAbierto()).isFalse();
        assertThat(h.cuandoTeAtendemos()).isEqualTo("mañana desde las 8:00 a. m.");
    }

    @Test
    void enLaMadrugadaSeAtiendeEseMismoDia() {
        assertThat(a(2026, 10, 20, 5, 0).cuandoTeAtendemos()).isEqualTo("hoy desde las 8:00 a. m.");
    }

    @Test
    void elSabadoEnLaNocheSeAtiendeElLunes() {
        assertThat(a(2026, 10, 24, 19, 0).cuandoTeAtendemos()).isEqualTo("el lunes desde las 8:00 a. m.");
    }

    @Test
    void laDescripcionEsLegibleParaElCliente() {
        assertThat(a(2026, 10, 19, 10, 0).descripcion()).isEqualTo("lunes a sábado de 8:00 a. m. a 6:00 p. m.");
    }
}
