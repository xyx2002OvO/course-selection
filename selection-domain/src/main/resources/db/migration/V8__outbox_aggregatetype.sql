ALTER TABLE outbox_event
  ADD COLUMN aggregatetype VARCHAR(16) NOT NULL DEFAULT 'requests' AFTER kind;
