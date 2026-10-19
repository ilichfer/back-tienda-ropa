package com.tiendaropa.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiendaropa.domain.repository.ClienteRepository;
import com.tiendaropa.domain.repository.CuentaMovimientoRepository;
import com.tiendaropa.domain.repository.CuentaRepository;
import com.tiendaropa.domain.repository.PedidoRepository;
import com.tiendaropa.domain.repository.SolicitudEnvioRepository;
import com.tiendaropa.domain.repository.WaMensajeRepository;
import com.tiendaropa.domain.service.CoexistenciaService;
import com.tiendaropa.domain.service.CuentaService;
import com.tiendaropa.domain.service.EnvioService;
import com.tiendaropa.domain.service.InconvenienteService;
import com.tiendaropa.domain.service.conversation.ConversationStateStore;
import com.tiendaropa.domain.service.intent.IntentDetector;
import com.tiendaropa.domain.service.whatsapp.WhatsAppCloudClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** El bot no le contesta encima a un cliente que alguien del equipo atiende desde el celular. */
class WhatsAppServiceCoexistenciaTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CLIENTE = "573001112233";

    private WhatsAppCloudClient cloud;
    private ConversationStateStore stateStore;
    private CoexistenciaService coexistencia;
    private WhatsAppServiceImpl servicio;

    @BeforeEach
    void setUp() {
        cloud = mock(WhatsAppCloudClient.class);
        stateStore = mock(ConversationStateStore.class);
        coexistencia = mock(CoexistenciaService.class);
        servicio = new WhatsAppServiceImpl(
                cloud,
                mock(WaMensajeRepository.class),
                mock(ClienteRepository.class),
                mock(EnvioService.class),
                mock(SolicitudEnvioRepository.class),
                mock(CuentaService.class),
                mock(CuentaRepository.class),
                mock(CuentaMovimientoRepository.class),
                mock(InconvenienteService.class),
                mock(PedidoRepository.class),
                List.of(),
                new IntentDetector(),
                stateStore,
                coexistencia,
                new com.tiendaropa.domain.service.HorarioAtencion("MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY", "00:00", "23:59", "America/Bogota", java.time.Clock.systemUTC()));
        ReflectionTestUtils.setField(servicio, "pausaBotMinutos", 60L);
    }

    @Test
    void unaRespuestaDesdeElCelularPausaElBotParaEseCliente() throws Exception {
        when(coexistencia.registrarEco(any())).thenReturn(CLIENTE);

        servicio.procesarWebhook(json("""
                {"entry":[{"changes":[{"field":"smb_message_echoes","value":{"message_echoes":[
                  {"to":"%s","id":"wamid.E","type":"text","text":{"body":"ya te atiendo"}}]}}]}]}
                """.formatted(CLIENTE)));

        verify(stateStore).marcarRespuestaHumana(CLIENTE);
        // Cualquier flujo que el bot tuviera a medias se descarta.
        verify(stateStore).remove(CLIENTE);
    }

    @Test
    void siElEquipoRespondioHacePocoElBotNoContesta() throws Exception {
        when(stateStore.getUltimaRespuestaHumana(CLIENTE)).thenReturn(Instant.now().minus(5, ChronoUnit.MINUTES));

        servicio.procesarWebhook(mensajeDeTexto("hola"));

        verify(cloud, never()).enviarMensajeYObtenerId(any());
    }

    @Test
    void pasadaLaPausaElBotVuelveAContestar() throws Exception {
        when(stateStore.getUltimaRespuestaHumana(CLIENTE)).thenReturn(Instant.now().minus(2, ChronoUnit.HOURS));

        servicio.procesarWebhook(mensajeDeTexto("hola"));

        // Primer mensaje del número: el bot manda el saludo de bienvenida.
        verify(cloud, atLeastOnce()).enviarMensajeYObtenerId(any());
    }

    private static JsonNode mensajeDeTexto(String texto) throws Exception {
        return json("""
                {"entry":[{"changes":[{"field":"messages","value":{"messages":[
                  {"from":"%s","id":"wamid.%s","type":"text","text":{"body":"%s"}}]}}]}]}
                """.formatted(CLIENTE, System.nanoTime(), texto));
    }

    private static JsonNode json(String texto) throws Exception {
        return JSON.readTree(texto);
    }
}
