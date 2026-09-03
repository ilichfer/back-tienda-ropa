-- Agrega la columna para "responder citando" un mensaje (la acción de WhatsApp Web de citar un
-- mensaje/foto al responder). Guarda el wa_message_id del mensaje original: tanto cuando se
-- manda una respuesta desde el panel citando algo, como cuando un cliente responde citando algo
-- desde su teléfono (WhatsApp incluye el "context.id" del mensaje citado en el webhook).
DO $$ BEGIN ALTER TABLE wa_mensajes ADD COLUMN IF NOT EXISTS context_wa_message_id VARCHAR(120); EXCEPTION WHEN others THEN NULL; END; $$;
