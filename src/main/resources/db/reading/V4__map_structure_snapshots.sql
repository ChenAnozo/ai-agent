ALTER TABLE reading_book_map ADD COLUMN nodes jsonb NOT NULL DEFAULT '[]';
UPDATE reading_book_map m SET nodes=d.nodes FROM reading_book_document d
WHERE m.kb_id=d.kb_id AND m.generation=d.generation AND m.status='READY';
