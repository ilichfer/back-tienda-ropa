package com.tiendaropa.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiendaropa.domain.model.Cliente;
import com.tiendaropa.domain.model.Cuenta;
import com.tiendaropa.domain.model.CuentaMovimiento;
import com.tiendaropa.domain.repository.ClienteRepository;
import com.tiendaropa.domain.repository.CuentaMovimientoRepository;
import com.tiendaropa.domain.repository.CuentaRepository;
import com.tiendaropa.domain.repository.PedidoRepository;
import com.tiendaropa.domain.repository.SolicitudEnvioRepository;
import com.tiendaropa.domain.repository.WaMensajeRepository;
import com.tiendaropa.domain.service.CoexistenciaService;
import com.tiendaropa.domain.service.CuentaService;
import com.tiendaropa.domain.service.EnvioService;
import com.tiendaropa.domain.service.HorarioAtencion;
import com.tiendaropa.domain.service.InconvenienteService;
import com.tiendaropa.domain.service.conversation.ConversationStateStore;
import com.tiendaropa.domain.service.intent.IntentDetector;
import com.tiendaropa.domain.service.whatsapp.WhatsAppCloudClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Horario de atención, resumen antes del envío y avisos de lives. */
class WhatsAppServiceExperienciaTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CLIENTE = "573001112233";

    private final WhatsAppCloudClient cloud = mock(WhatsAppCloudClient.class);
    private final ClienteRepository clienteRepo = mock(ClienteRepository.class);
    private final CuentaRepository cuentaRepo = mock(CuentaRepository.class);
    private final CuentaMovimientoRepository movimientoRepo = mock(CuentaMovimientoRepository.class);
    private final Cliente cliente = Cliente.builder().whatsapp(CLIENTE).build();

    private WhatsAppServiceImpl servicio(HorarioAtencion horario) {
        when(clienteRepo.findByWhatsapp(CLIENTE)).thenReturn(Optional.of(cliente));
        var s = new WhatsAppServiceImpl(cloud, mock(WaMensajeRepository.class), clienteRepo,
                mock(EnvioService.class), mock(SolicitudEnvioRepository.class), mock(CuentaService.class),
                cuentaRepo, movimientoRepo, mock(InconvenienteService.class), mock(PedidoRepository.class),
                List.of(), new IntentDetector(), mock(ConversationStateStore.class),
                mock(CoexistenciaService.class), horario);
        ReflectionTestUtils.setField(s, "pausaBotMinutos", 60L);
        return s;
    }

    private static HorarioAtencion lunesA(int hora) {
        var zona = ZoneId.of("America/Bogota");
        var instante = ZonedDateTime.of(2026, 10, 19, hora, 0, 0, 0, zona).toInstant();
        return new HorarioAtencion("MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY", "08:00", "18:00",
                "America/Bogota", Clock.fixed(instante, zona));
    }

    @Test
    void fueraDeHorarioElClienteSabeCuandoLoAtienden() throws Exception {
        servicio(lunesA(22)).procesarWebhook(texto("quiero hablar con un asesor"));

        assertThat(textosEnviados()).anySatisfy(t ->
                assertThat(t).contains("lunes a sábado").contains("mañana desde las 8:00 a. m."));
    }

    @Test
    void alPreguntarPorElLiveSeOfrecenAvisosYElBotonSuscribe() throws Exception {
        var s = servicio(lunesA(10));
        s.procesarWebhook(texto("cuándo es el próximo live?"));
        assertThat(textosEnviados()).anySatisfy(t -> assertThat(t).contains("avisemos"));

        s.procesarWebhook(boton("lives_si"));
        assertThat(cliente.getAvisosLive()).isTrue();

        s.procesarWebhook(texto("no más avisos"));
        assertThat(cliente.getAvisosLive()).isFalse();
    }

    @Test
    void alTerminarLosDatosDeEnvioSeMandaElResumenDelPedido() throws Exception {
        var cuenta = Cuenta.builder().id(UUID.randomUUID()).cliente(cliente).build();
        when(cuentaRepo.findByCliente_Whatsapp(CLIENTE)).thenReturn(Optional.of(cuenta));
        when(movimientoRepo.findByCuentaIdOrderByCreatedAtDesc(cuenta.getId())).thenReturn(List.of(
                CuentaMovimiento.builder().tipo("CARGO").concepto("Jean talla 32").valor(45000L).build(),
                CuentaMovimiento.builder().tipo("CARGO").concepto("Blusa azul").build(),
                CuentaMovimiento.builder().tipo("ABONO").valor(20000L).build()));
        when(movimientoRepo.sumCargos(cuenta.getId())).thenReturn(45000L);
        when(movimientoRepo.sumAbonos(cuenta.getId())).thenReturn(20000L);

        servicio(lunesA(10)).procesarWebhook(json("""
                {"entry":[{"changes":[{"field":"messages","value":{"messages":[
                  {"from":"%s","id":"wamid.F","type":"interactive","interactive":{"type":"nfm_reply",
                   "nfm_reply":{"response_json":"{\\"nombre\\":\\"Ana\\",\\"ciudad\\":\\"Cali\\"}"}}}]}}]}]}
                """.formatted(CLIENTE)));

        assertThat(textosEnviados()).anySatisfy(t -> assertThat(t)
                .contains("Resumen de tu pedido")
                .contains("Jean talla 32 — $45.000")
                .contains("Blusa azul — valor por confirmar")
                .contains("Saldo pendiente: *$25.000*"));
    }

    /** Texto de cada mensaje que el bot mandó (cuerpo de texto o de botones). */
    @SuppressWarnings("unchecked")
    private List<String> textosEnviados() {
        var captor = ArgumentCaptor.forClass(Map.class);
        verify(cloud, atLeastOnce()).enviarMensajeYObtenerId(captor.capture());
        return captor.getAllValues().stream().map(b -> {
            var body = (Map<String, Object>) b;
            if (body.get("text") instanceof Map<?, ?> t) return String.valueOf(t.get("body"));
            if (body.get("interactive") instanceof Map<?, ?> i && i.get("body") instanceof Map<?, ?> ib)
                return String.valueOf(ib.get("text"));
            return "";
        }).toList();
    }

    private static JsonNode texto(String texto) throws Exception {
        return json("""
                {"entry":[{"changes":[{"field":"messages","value":{"messages":[
                  {"from":"%s","id":"wamid.%s","type":"text","text":{"body":"%s"}}]}}]}]}
                """.formatted(CLIENTE, System.nanoTime(), texto));
    }

    private static JsonNode boton(String id) throws Exception {
        return json("""
                {"entry":[{"changes":[{"field":"messages","value":{"messages":[
                  {"from":"%s","id":"wamid.%s","type":"interactive",
                   "interactive":{"type":"button_reply","button_reply":{"id":"%s","title":"x"}}}]}}]}]}
                """.formatted(CLIENTE, System.nanoTime(), id));
    }

    private static JsonNode json(String texto) throws Exception {
        return JSON.readTree(texto);
    }
}
