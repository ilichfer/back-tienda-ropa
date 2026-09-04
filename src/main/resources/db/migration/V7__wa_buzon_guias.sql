-- Marca un chat (no un cliente real) como la fuente de donde llegan las fotos de guías de
-- envío (ej. Interrápidísimo), para poder reenviarlas manualmente al chat del cliente que
-- corresponda. Al marcarlo, el panel también activa bot_silenciado (no tiene sentido que el
-- bot salude o interprete imágenes ahí).
DO $$ BEGIN ALTER TABLE clientes ADD COLUMN IF NOT EXISTS es_buzon_guias BOOLEAN DEFAULT FALSE; EXCEPTION WHEN others THEN NULL; END; $$;
