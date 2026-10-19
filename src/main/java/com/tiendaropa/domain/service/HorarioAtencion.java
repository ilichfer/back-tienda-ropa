package com.tiendaropa.domain.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Horario en que hay personas del equipo atendiendo el chat. Cuando un cliente pide un asesor
 * fuera de este horario, el bot no le dice "en breve" (y lo deja esperando toda la noche), sino
 * cuándo lo van a atender de verdad. Se configura con atencion.* sin tocar código.
 */
@Component
public class HorarioAtencion {

    private static final Locale ES = Locale.forLanguageTag("es-CO");

    private final Set<DayOfWeek> dias;
    private final LocalTime inicio;
    private final LocalTime fin;
    private final ZoneId zona;
    private final Clock clock;

    @Autowired
    public HorarioAtencion(
            @Value("${atencion.dias:MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY}") String dias,
            @Value("${atencion.hora-inicio:08:00}") String inicio,
            @Value("${atencion.hora-fin:18:00}") String fin,
            @Value("${atencion.zona-horaria:America/Bogota}") String zona) {
        this(dias, inicio, fin, zona, Clock.systemUTC());
    }

    public HorarioAtencion(String dias, String inicio, String fin, String zona, Clock clock) {
        this.dias = EnumSet.noneOf(DayOfWeek.class);
        Arrays.stream(dias.split(",")).map(String::trim).filter(d -> !d.isEmpty())
                .map(d -> DayOfWeek.valueOf(d.toUpperCase())).forEach(this.dias::add);
        this.inicio = LocalTime.parse(inicio);
        this.fin = LocalTime.parse(fin);
        this.zona = ZoneId.of(zona);
        this.clock = clock;
    }

    public boolean estaAbierto() {
        var ahora = ZonedDateTime.now(clock).withZoneSameInstant(zona);
        var hora = ahora.toLocalTime();
        return dias.contains(ahora.getDayOfWeek()) && !hora.isBefore(inicio) && hora.isBefore(fin);
    }

    /** "en breve" si hay alguien atendiendo; si no, "hoy desde las 8:00 a. m.", "mañana desde…", "el lunes desde…". */
    public String cuandoTeAtendemos() {
        if (estaAbierto() || dias.isEmpty()) return "en breve";
        var hoy = ZonedDateTime.now(clock).withZoneSameInstant(zona).toLocalDate();
        var hora = ZonedDateTime.now(clock).withZoneSameInstant(zona).toLocalTime();
        for (int i = 0; i < 8; i++) {
            var dia = hoy.plusDays(i);
            if (!dias.contains(dia.getDayOfWeek())) continue;
            if (i == 0 && !hora.isBefore(inicio)) continue; // hoy ya cerró
            var cuando = switch (i) {
                case 0 -> "hoy";
                case 1 -> "mañana";
                default -> "el " + dia.getDayOfWeek().getDisplayName(TextStyle.FULL, ES);
            };
            return cuando + " desde las " + formatoHora(inicio);
        }
        return "en breve";
    }

    /** Ej. "lunes a sábado de 8:00 a. m. a 6:00 p. m." para mostrarlo al cliente. */
    public String descripcion() {
        var ordenados = dias.stream().sorted().toList();
        String rango;
        if (ordenados.isEmpty()) rango = "";
        else if (ordenados.size() == 1) rango = nombre(ordenados.get(0));
        else rango = nombre(ordenados.get(0)) + " a " + nombre(ordenados.get(ordenados.size() - 1));
        return rango + " de " + formatoHora(inicio) + " a " + formatoHora(fin);
    }

    private static String nombre(DayOfWeek d) {
        return d.getDisplayName(TextStyle.FULL, ES);
    }

    private static String formatoHora(LocalTime t) {
        var h = t.getHour() % 12 == 0 ? 12 : t.getHour() % 12;
        return "%d:%02d %s".formatted(h, t.getMinute(), t.getHour() < 12 ? "a. m." : "p. m.");
    }
}
