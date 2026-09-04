-- Estado de entrega de los mensajes salientes (sent / delivered / read / failed), que llega
-- por el webhook de "statuses" de Meta (hasta ahora ignorado por completo: procesarWebhook
-- solo procesaba el array "messages"). error_entrega guarda el motivo cuando status=failed.
DO $$ BEGIN ALTER TABLE wa_mensajes ADD COLUMN IF NOT EXISTS estado_entrega VARCHAR(20); EXCEPTION WHEN others THEN NULL; END; $$;
DO $$ BEGIN ALTER TABLE wa_mensajes ADD COLUMN IF NOT EXISTS error_entrega TEXT; EXCEPTION WHEN others THEN NULL; END; $$;
