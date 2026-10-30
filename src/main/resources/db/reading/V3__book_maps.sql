CREATE TABLE reading_book_document (
 kb_id text NOT NULL REFERENCES reading_kb(id) ON DELETE CASCADE,
 generation text NOT NULL, text_content text NOT NULL, nodes jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(kb_id,generation)
);
CREATE TABLE reading_book_chunk (
 kb_id text NOT NULL, generation text NOT NULL, vector_id uuid NOT NULL,
 ordinal integer NOT NULL, start_pos integer NOT NULL, end_pos integer NOT NULL, node_id text NOT NULL,
 PRIMARY KEY(kb_id,generation,vector_id),
 FOREIGN KEY(kb_id,generation) REFERENCES reading_book_document(kb_id,generation) ON DELETE CASCADE
);
CREATE TABLE reading_book_map (
 id text PRIMARY KEY, kb_id text NOT NULL REFERENCES reading_kb(id) ON DELETE CASCADE,
 generation text NOT NULL, status text NOT NULL, error text NOT NULL DEFAULT '',
 summaries jsonb NOT NULL DEFAULT '{}', overview text NOT NULL DEFAULT '',
 model_id text NOT NULL, prompt_version text NOT NULL, method text NOT NULL DEFAULT '',
 usage jsonb NOT NULL DEFAULT '[]', created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX reading_book_map_current ON reading_book_map(kb_id,generation,created_at DESC);
