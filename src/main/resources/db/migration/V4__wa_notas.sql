-- Notas internas por conversación de WhatsApp: comentarios que solo ven los asesores
-- (nunca se le mandan al cliente), para dejar contexto en el handoff entre turnos
-- ("dijo que paga el viernes", etc). No hay tabla de usuarios en el sistema, así que
-- "autor" es un campo de texto libre opcional, no una relación forzada.
CREATE TABLE IF NOT EXISTS wa_notas (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    whatsapp_from   VARCHAR(50) NOT NULL,
    autor           VARCHAR(80),
    contenido       TEXT NOT NULL,
    created_at      TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_wa_notas_from ON wa_notas(whatsapp_from);
