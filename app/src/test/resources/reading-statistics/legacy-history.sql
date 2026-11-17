-- History schema before migration 30. Keep this fixture independent of current schema.
CREATE TABLE history(
    _id INTEGER NOT NULL PRIMARY KEY,
    chapter_id INTEGER NOT NULL UNIQUE,
    last_read INTEGER,
    time_read INTEGER NOT NULL,
    FOREIGN KEY(chapter_id) REFERENCES chapters (_id)
    ON DELETE CASCADE
);
CREATE INDEX history_history_chapter_id_index ON history(chapter_id);
CREATE INDEX idx_history_last_read ON history(last_read);
