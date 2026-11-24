ALTER TABLE public.messages ADD COLUMN IF NOT EXISTS message_type TEXT NOT NULL DEFAULT 'text';
CREATE TABLE IF NOT EXISTS public.message_media (message_id BIGINT PRIMARY KEY REFERENCES public.messages(id) ON DELETE CASCADE, mime_type TEXT NOT NULL, file_name TEXT NOT NULL, file_size INTEGER NOT NULL CHECK (file_size > 0 AND file_size <= 12582912), data BYTEA NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW());
CREATE INDEX IF NOT EXISTS messages_chat_type_created_idx ON public.messages(chat_id, message_type, created_at DESC);
