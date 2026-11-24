-- Heartlink Phase 2 — T04 (Flyway V2)
-- Pre-condition: V1 schema exists (either migrated from empty DB or audited + baselined at version 1).
-- Contract:
--   1. Audit existing data in swipes (no self-swipe) and matches (canonical pair user_a_id < user_b_id)
--      WITHOUT modifying or deleting any existing rows.
--   2. Abort transactionally with a clear diagnostic error if any historical row violates the invariant.
--   3. Add chk_swipes_no_self and chk_matches_canonical_pair only after the data audit passes.

DO $$
DECLARE
    v_self_swipes     BIGINT;
    v_first_swipe_id  BIGINT;
    v_bad_matches     BIGINT;
    v_first_match_id  BIGINT;
BEGIN
    SELECT count(*), min(id)
      INTO v_self_swipes, v_first_swipe_id
      FROM swipes
     WHERE swiper_id = target_id;

    IF v_self_swipes > 0 THEN
        RAISE EXCEPTION
            '[T04-DATA-AUDIT] Cannot add chk_swipes_no_self: found % self-swipe row(s) in swipes (first id=%). Inspect and remediate manually before upgrading.',
            v_self_swipes, v_first_swipe_id;
    END IF;

    SELECT count(*), min(id)
      INTO v_bad_matches, v_first_match_id
      FROM matches
     WHERE user_a_id >= user_b_id;

    IF v_bad_matches > 0 THEN
        RAISE EXCEPTION
            '[T04-DATA-AUDIT] Cannot add chk_matches_canonical_pair: found % non-canonical/self row(s) in matches where user_a_id >= user_b_id (first id=%). Inspect and remediate manually before upgrading.',
            v_bad_matches, v_first_match_id;
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = 'public.swipes'::regclass
           AND conname = 'chk_swipes_no_self'
    ) THEN
        ALTER TABLE swipes
            ADD CONSTRAINT chk_swipes_no_self CHECK (swiper_id <> target_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = 'public.matches'::regclass
           AND conname = 'chk_matches_canonical_pair'
    ) THEN
        ALTER TABLE matches
            ADD CONSTRAINT chk_matches_canonical_pair CHECK (user_a_id < user_b_id);
    END IF;
END $$;

