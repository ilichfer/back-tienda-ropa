package com.tiendaropa.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiendaropa.domain.model.Cliente;
import com.tiendaropa.domain.model.WaMensaje;
import com.tiendaropa.domain.repository.ClienteRepository;
import com.tiendaropa.domain.repository.WaMensajeRepository;
import com.tiendaropa.domain.service.whatsapp.WhatsAppCloudClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CoexistenciaServiceImplTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private WaMensajeRepository mensajeRepo;
    private ClienteRepository clienteRepo;
    private CoexistenciaServiceImpl servicio;

    @BeforeEach
    void setUp() {
        mensajeRepo = mock(WaMensajeRepository.class);
        clienteRepo = mock(ClienteRepository.class);
        servicio = new CoexistenciaServiceImpl(mock(WhatsAppCloudClient.class), mensajeRepo, clienteRepo);
        when(mensajeRepo.save(any())).thenAnswer(inv -> {
            WaMensaje m = inv.getArgument(0);
            m.setId(UUID.randomUUID());
            return m;
        });
    }

    @Test
    void elHistorialGuardaEntradasYSalidasLeidasYConSuFechaOriginal() throws Exception {
        servicio.procesarHistorial(json("""
                {"history":[{"metadata":{"phase":0,"chunk_order":1,"progress":100},
                  "threads":[{"id":"573001112233","messages":[
                    {"from":"573001112233","id":"wamid.A","timestamp":"1700000000","type":"text","text":{"body":"hola"}},
                    {"from":"573229063035","id":"wamid.B","timestamp":"1700000100","type":"text","text":{"body":"buenas"},
                     "history_context":{"status":"READ"}}
                  ]}]}]}
                """));

        var guardados = ArgumentCaptor.forClass(WaMensaje.class);
        verify(mensajeRepo, org.mockito.Mockito.times(2)).save(guardados.capture());
        var entrada = guardados.getAllValues().get(0);
        var salida = guardados.getAllValues().get(1);

        assertThat(entrada.getDireccion()).isEqualTo("ENTRADA");
        assertThat(entrada.getContenido()).isEqualTo("hola");
        assertThat(entrada.getLeido()).isTrue();
        assertThat(salida.getDireccion()).isEqualTo("SALIDA");
        assertThat(salida.getEstadoEntrega()).isEqualTo("read");
        verify(mensajeRepo).actualizarFecha(eq(entrada.getId()), eq(Instant.ofEpochSecond(1700000000)));
    }

    @Test
    void lasReaccionesDelHistorialNoSeGuardan() throws Exception {
        servicio.procesarHistorial(json("""
                {"history":[{"threads":[{"id":"573001112233","messages":[
                  {"from":"573001112233","id":"wamid.R","type":"reaction","reaction":{"emoji":"👍"}}
                ]}]}]}
                """));
        verify(mensajeRepo, never()).save(any());
    }

    @Test
    void unEcoDelCelularQuedaComoSalidaYYaNoRequiereAsesor() throws Exception {
        var cliente = Cliente.builder().whatsapp("573001112233").requiereAsesor(true).build();
        when(clienteRepo.findByWhatsapp("573001112233")).thenReturn(Optional.of(cliente));

        var numero = servicio.registrarEco(json("""
                {"from":"573229063035","to":"573001112233","id":"wamid.E","timestamp":"1700000200",
                 "type":"text","text":{"body":"ya te la separo"}}
                """));

        assertThat(numero).isEqualTo("573001112233");
        var guardado = ArgumentCaptor.forClass(WaMensaje.class);
        verify(mensajeRepo).save(guardado.capture());
        assertThat(guardado.getValue().getDireccion()).isEqualTo("SALIDA");
        assertThat(cliente.getRequiereAsesor()).isFalse();
    }

    @Test
    void unEcoRepetidoNoSeGuardaDosVeces() throws Exception {
        when(mensajeRepo.findByWaMessageId("wamid.E")).thenReturn(Optional.of(new WaMensaje()));

        var numero = servicio.registrarEco(json("""
                {"to":"573001112233","id":"wamid.E","type":"text","text":{"body":"hola"}}
                """));

        assertThat(numero).isEqualTo("573001112233");
        verify(mensajeRepo, never()).save(any());
    }

    @Test
    void losContactosNoPisanElNombreQuePusoElEquipo() throws Exception {
        var cliente = Cliente.builder().whatsapp("573001112233").nombre("Ana (TikTok @ana)").build();
        when(clienteRepo.findByWhatsapp("573001112233")).thenReturn(Optional.of(cliente));

        servicio.procesarContactos(json("""
                {"state_sync":[{"type":"contact","action":"add",
                  "contact":{"full_name":"Ana María","phone_number":"+57 300 111 2233"}}]}
                """));

        assertThat(cliente.getNombre()).isEqualTo("Ana (TikTok @ana)");
        verify(clienteRepo, never()).save(any());
        verify(mensajeRepo).vincularCliente(cliente, "573001112233");
    }

    private static JsonNode json(String texto) throws Exception {
        return JSON.readTree(texto);
    }
}
