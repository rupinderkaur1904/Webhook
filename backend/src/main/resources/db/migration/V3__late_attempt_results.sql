-- A worker whose lease was reaped may still finish its request later. Its observation is kept on the
-- abandoned attempt for an honest history, flagged so it is never mistaken for an applied result.
ALTER TABLE delivery_attempts ADD COLUMN reported_after_lease_expiry boolean NOT NULL DEFAULT false;
