CREATE TABLE reading_attachment (
 id text PRIMARY KEY,
 session_id text NOT NULL REFERENCES reading_session(id) ON DELETE CASCADE,
 filename text NOT NULL, format text NOT NULL, file_size bigint NOT NULL,
 character_count integer NOT NULL, sections jsonb NOT NULL,
 active boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX reading_attachment_session ON reading_attachment(session_id);
