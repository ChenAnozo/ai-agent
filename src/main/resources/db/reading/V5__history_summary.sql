CREATE TABLE reading_history_summary (
 session_id text PRIMARY KEY REFERENCES reading_session(id) ON DELETE CASCADE,
 summary text NOT NULL,
 covered_run_id text NOT NULL,
 pending_run_id text NOT NULL DEFAULT '',
 pending_offset integer NOT NULL DEFAULT 0
);
