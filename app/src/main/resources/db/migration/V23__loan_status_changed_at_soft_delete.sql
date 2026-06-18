-- V23: loan-core enhancements (Phase 2 T3)
--   * status_changed_at — mirror of the latest status-transition time, so pipeline stage-age sorting
--     does not have to join loan_status_history (backfilled from history, COALESCE created_at).
--   * deleted_at — soft-delete tombstone; a regulated mortgage LOS must never hard-delete loans.
--   * transitioned_at on loan_status_history — supports status backdating (the recorded effective
--     time of the transition, distinct from the audit created_at write time).
-- RLS already FORCEd on loan / loan_status_history (V3); the new columns inherit it.

alter table loan add column status_changed_at timestamptz;
alter table loan add column deleted_at timestamptz;

alter table loan_status_history add column transitioned_at timestamptz;

-- Existing history rows have no explicit transitioned_at: seed it from the audited write time so
-- the max() backfill below sees a value (and future stage-age sorts have a transition time).
update loan_status_history set transitioned_at = created_at where transitioned_at is null;

-- Backfill status_changed_at = latest transition time for the loan, else the loan's created_at.
update loan
   set status_changed_at = coalesce(
        (select max(h.transitioned_at) from loan_status_history h where h.loan_id = loan.id),
        loan.created_at);

create index idx_loan_org_status_changed on loan (org_id, status, status_changed_at);
