CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE reading_kb (
 id text PRIMARY KEY, name text NOT NULL, filename text NOT NULL, format text NOT NULL,
 path text NOT NULL, status text NOT NULL, error text NOT NULL DEFAULT '',
 chunk_count integer NOT NULL DEFAULT 0, model_id text, dimensions integer,
 generation text NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
-- Exact search; rebuild explicitly when the embedding model changes.
CREATE TABLE reading_vectors (
 id uuid PRIMARY KEY, content text NOT NULL, metadata jsonb NOT NULL, embedding vector NOT NULL
);
CREATE INDEX reading_vectors_kb ON reading_vectors ((metadata->>'kbId'));
CREATE TABLE reading_session (
 id text PRIMARY KEY, title text NOT NULL, settings jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE reading_run (
 id text PRIMARY KEY, session_id text NOT NULL REFERENCES reading_session(id) ON DELETE CASCADE,
 question text NOT NULL, answer text NOT NULL DEFAULT '', status text NOT NULL,
 snapshot jsonb NOT NULL, sources jsonb NOT NULL DEFAULT '[]', events jsonb NOT NULL DEFAULT '[]',
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX reading_run_session ON reading_run(session_id, created_at);
CREATE TABLE reading_prompt (id text PRIMARY KEY, name text NOT NULL, content text NOT NULL);
