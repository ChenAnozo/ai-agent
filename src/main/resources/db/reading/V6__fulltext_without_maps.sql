-- Existing full text and passage coordinates remain valid without a map.
DROP TABLE reading_book_map;
ALTER TABLE reading_book_document DROP COLUMN nodes;
ALTER TABLE reading_book_chunk DROP COLUMN node_id;

-- Older READY knowledge bases may only have vectors, without full text/positions.
UPDATE reading_kb k SET status='FAILED', error='知识库缺少完整原文或片段位置，请重新入库'
WHERE k.status='READY' AND (
    NOT EXISTS (SELECT 1 FROM reading_book_document d WHERE d.kb_id=k.id AND d.generation=k.generation)
    OR k.chunk_count=0
    OR k.chunk_count <> (SELECT count(*) FROM reading_book_chunk c WHERE c.kb_id=k.id AND c.generation=k.generation)
);
