-- Spring Modulith transactional outbox (S0.3). Platform-scoped, no RLS.
CREATE TABLE event_publication (
    id                     uuid PRIMARY KEY,
    listener_id            text NOT NULL,
    event_type             text NOT NULL,
    serialized_event       text NOT NULL,
    publication_date       timestamptz NOT NULL,
    completion_date        timestamptz,
    last_resubmission_date timestamptz,
    completion_attempts    int NOT NULL DEFAULT 0,
    status                 varchar(20)
);
CREATE INDEX event_publication_incomplete_idx ON event_publication (publication_date) WHERE completion_date IS NULL;
