-- =================================================================================
-- V3__rag_chunk_types_and_model_index.sql
-- Permite fragmentos de contenido documental completo (DOCUMENT_CONTENT) y optimiza
-- el filtrado por modelo y versión de embedding por usuario en document_chunks.
-- =================================================================================

DO $$
DECLARE
    chk_name TEXT;
BEGIN
    SELECT c.conname INTO chk_name
    FROM pg_constraint c
    JOIN pg_class t ON t.oid = c.conrelid
    WHERE t.relname = 'document_chunks'
      AND c.contype = 'c'
      AND pg_get_constraintdef(c.oid) LIKE '%chunk_type%';

    IF chk_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE document_chunks DROP CONSTRAINT %I;', chk_name);
    END IF;
END $$;

ALTER TABLE document_chunks
    ADD CONSTRAINT chk_document_chunks_chunk_type
        CHECK (chunk_type IN ('INVOICE_SUMMARY', 'ITEM_GROUP', 'MANUAL_EXPENSE', 'STATEMENT_WINDOW', 'DOCUMENT_CONTENT'));

CREATE INDEX IF NOT EXISTS idx_document_chunks_user_model
    ON document_chunks (user_id, embedding_model, embedding_version)
    WHERE embedding IS NOT NULL;
