-- Cuándo pidió el cliente un asesor (o el bot lo marcó como "requiere asesor"), para mostrar en
-- el panel cuánto lleva esperando. Se limpia cuando deja de requerir asesor.
ALTER TABLE clientes ADD COLUMN IF NOT EXISTS asesor_solicitado_en TIMESTAMPTZ;

-- El cliente aceptó que le avisemos por WhatsApp cuándo es el próximo live (botón del bot).
ALTER TABLE clientes ADD COLUMN IF NOT EXISTS avisos_live BOOLEAN DEFAULT FALSE;

-- Chats antiguos (sin mensajes hace más de 45 días) que el equipo revisó y decidió conservar.
-- Mientras la fecha de conservación sea reciente, el chat no vuelve a aparecer en el aviso.
CREATE TABLE IF NOT EXISTS wa_chats_conservados (
    whatsapp_from   VARCHAR(50) PRIMARY KEY,
    conservado_en   TIMESTAMPTZ NOT NULL DEFAULT now()
);
