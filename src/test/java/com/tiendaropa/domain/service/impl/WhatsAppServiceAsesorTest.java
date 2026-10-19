package com.tiendaropa.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiendaropa.domain.model.Cliente;
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
import com.tiendaropa.web.controller.WaMensajeController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Cuando el cliente pide un asesor, el bot se calla del todo hasta que lo reactiven desde el panel. */
class WhatsAppServiceAsesorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CLIENTE = "573001112233";

    private WhatsAppCloudClient cloud;
    private ClienteRepository clienteRepo;
    private ConversationStateStore stateStore;
    private Cliente cliente;
    private WhatsAppServiceImpl servicio;

    @BeforeEach
    void setUp() {
        cloud = mock(WhatsAppCloudClient.class);
        clienteRepo = mock(ClienteRepository.class);
        stateStore = mock(ConversationStateStore.class);
        cliente = Cliente.builder().whatsapp(CLIENTE).build();
        when(clienteRepo.findByWhatsapp(CLIENTE)).thenReturn(Optional.of(cliente));
        servicio = new WhatsAppServiceImpl(
                cloud,
                mock(WaMensajeRepository.class),
                clienteRepo,
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
                mock(CoexistenciaService.class),
                new com.tiendaropa.domain.service.HorarioAtencion("MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY", "00:00", "23:59", "America/Bogota", java.time.Clock.systemUTC()));
        ReflectionTestUtils.setField(servicio, "pausaBotMinutos", 60L);
    }

    @Test
    void pedirUnAsesorPorTextoSilenciaElBotConUnSoloMensaje() throws Exception {
        servicio.procesarWebhook(texto("quiero hablar con una asesora por favor"));

        assertThat(cliente.getBotSilenciado()).isTrue();
        assertThat(cliente.getRequiereAsesor()).isTrue();
        verify(stateStore).remove(CLIENTE);
        // Solo el aviso de "te comunico con un asesor": ni saludo de bienvenida ni botones.
        verify(cloud, times(1)).enviarMensajeYObtenerId(any());
    }

    @Test
    void elBotonHablarConAsesorTambienSilenciaElBot() throws Exception {
        servicio.procesarWebhook(json("""
                {"entry":[{"changes":[{"field":"messages","value":{"messages":[
                  {"from":"%s","id":"wamid.B","type":"interactive",
                   "interactive":{"type":"button_reply","button_reply":{"id":"asesora","title":"💬 Hablar con asesor"}}}]}}]}]}
                """.formatted(CLIENTE)));

        assertThat(cliente.getBotSilenciado()).isTrue();
        verify(cloud, times(1)).enviarMensajeYObtenerId(any());
    }

    @Test
    void conElBotSilenciadoNoSaleNingunMensajeAutomatico() throws Exception {
        cliente.setBotSilenciado(true);

        servicio.procesarWebhook(texto("hola, cuánto debo?"));
        servicio.procesarWebhook(texto("quiero mi pedido"));

        verify(cloud, never()).enviarMensajeYObtenerId(any());
    }

    @Test
    void reactivarElBotDesdeElPanelQuitaTambienLaMarcaDeAsesor() {
        cliente.setBotSilenciado(true);
        cliente.setRequiereAsesor(true);
        var controller = new WaMensajeController(mock(WaMensajeRepository.class), clienteRepo,
                mock(com.tiendaropa.domain.service.WhatsAppService.class));

        controller.silenciarBot(Map.of("whatsappFrom", CLIENTE, "silenciado", false));

        assertThat(cliente.getBotSilenciado()).isFalse();
        assertThat(cliente.getRequiereAsesor()).isFalse();
    }

    private static JsonNode texto(String texto) throws Exception {
        return json("""
                {"entry":[{"changes":[{"field":"messages","value":{"messages":[
                  {"from":"%s","id":"wamid.%s","type":"text","text":{"body":"%s"}}]}}]}]}
                """.formatted(CLIENTE, System.nanoTime(), texto));
    }

    private static JsonNode json(String texto) throws Exception {
        return JSON.readTree(texto);
    }
}
