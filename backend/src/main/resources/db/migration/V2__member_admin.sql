-- Phase 4.#3 (M0): account status + token version so an admin can revoke outstanding JWTs.
ALTER TABLE member
    ADD COLUMN status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' AFTER role,
    ADD COLUMN token_version INT NOT NULL DEFAULT 0 AFTER status,
    ADD CONSTRAINT chk_member_status CHECK (status IN ('ACTIVE', 'DISABLED'));
