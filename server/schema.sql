CREATE TABLE IF NOT EXISTS users (
  id BIGSERIAL PRIMARY KEY,
  username VARCHAR(24) NOT NULL,
  password_hash TEXT NOT NULL,
  display_name VARCHAR(40) NOT NULL,
  bio VARCHAR(160) NOT NULL DEFAULT '',
  avatar_url TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT users_username_format CHECK (username ~ '^[A-Za-z0-9_]{3,24}$')
);
CREATE UNIQUE INDEX IF NOT EXISTS users_username_lower_unique ON users (LOWER(username));

CREATE TABLE IF NOT EXISTS chats (
  id BIGSERIAL PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE IF NOT EXISTS chat_members (
  chat_id BIGINT NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  last_read_at TIMESTAMPTZ,
  joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (chat_id, user_id)
);
CREATE INDEX IF NOT EXISTS chat_members_user_idx ON chat_members(user_id);

CREATE TABLE IF NOT EXISTS messages (
  id BIGSERIAL PRIMARY KEY,
  chat_id BIGINT NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
  sender_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  body VARCHAR(4000) NOT NULL CHECK (char_length(body) BETWEEN 1 AND 4000),
  message_type TEXT NOT NULL DEFAULT 'text' CHECK (message_type IN ('text', 'image', 'audio')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS messages_chat_created_idx ON messages(chat_id, created_at DESC);


-- Private chat media, returned only after a server-side chat membership check.
ALTER TABLE messages ADD COLUMN IF NOT EXISTS message_type TEXT NOT NULL DEFAULT 'text';
CREATE TABLE IF NOT EXISTS message_media (message_id BIGINT PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE, mime_type TEXT NOT NULL, file_name TEXT NOT NULL, file_size INTEGER NOT NULL CHECK (file_size > 0 AND file_size <= 12582912), data BYTEA NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW());
CREATE INDEX IF NOT EXISTS messages_chat_type_created_idx ON messages(chat_id, message_type, created_at DESC);
