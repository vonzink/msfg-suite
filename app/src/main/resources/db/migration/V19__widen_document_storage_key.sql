-- Phase 1 / Task 5: the presigned-upload storage key uses the S3 layout
--   applications/{loanId}/{partyRole}/{typeName}/{docId}-{safeFilename}
-- which embeds two 36-char UUIDs plus a (≤200-char) sanitized filename — routinely exceeding the
-- original varchar(120) from V13. Widen the column so the spec'd key layout fits. Additive + safe
-- (a widening alter never rejects existing rows). document_content.storage_key mirrors the document
-- key, so widen it too for consistency.
alter table document         alter column storage_key type varchar(1024);
alter table document_content alter column storage_key type varchar(1024);
