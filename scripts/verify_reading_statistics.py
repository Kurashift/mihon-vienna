"""Exercise the actual SQL schema, migration and retention invariants in SQLite."""
import re
import sqlite3
import time
import unittest

from verify_library_queries import ROOT, SQL, add_chapter, add_manga, create_database


def statement(name):
    source = (SQL / "data/history.sq").read_text(encoding="utf-8")
    return re.search(rf"^{name}:\s*(.*?)(?=^\w+:|\Z)", source, re.M | re.S)[1].split(";", 1)[0]


def plain_sql(source):
    return re.sub(r"\b(INTEGER|TEXT|BLOB) AS \w+(?:<[^>]+>)?", r"\1", source)


def execute_script_transactionally(database, source):
    # executescript commits first. Use complete_statement so a failing migration really rolls back.
    pending = ""
    with database:
        for line in plain_sql(source).splitlines(keepends=True):
            pending += line
            if sqlite3.complete_statement(pending):
                database.execute(pending)
                pending = ""
    assert not pending.strip()


class ReadingStatisticsSqlTest(unittest.TestCase):
    def setUp(self):
        self.db = create_database(reading_triggers=True)
        self.addCleanup(self.db.close)
        self.db.execute("PRAGMA foreign_keys=ON")
        add_manga(self.db, 1, favorite=0)
        add_chapter(self.db, 10, 1)
        add_chapter(self.db, 20, 1)
        self.db.commit()

    def record(self, chapter=10):
        return self.db.execute("SELECT * FROM history WHERE chapter_id=?", (chapter,)).fetchone()

    def overview(self):
        return tuple(self.db.execute(statement("getReadingOverview")).fetchone())

    def visit(self, chapter=10, at=1000, duration=300):
        self.db.execute(statement("upsert"), {"chapterId": chapter, "readAt": at, "time_read": duration})

    def test_baselines_do_not_count_as_reading(self):
        self.assertEqual(2, self.db.execute("SELECT count(*) FROM history").fetchone()[0])
        self.assertEqual((0, 0, 0, 0), self.overview())
        self.assertTrue(all(r[0] for r in self.db.execute("SELECT hidden FROM history")))

    def test_manual_read_unread_retracts_count_without_changing_time(self):
        self.db.execute("UPDATE chapters SET read=1,last_page_read=22 WHERE _id=10")
        self.assertEqual((1, 0, 1, 0), self.overview())
        first_version = self.record()["state_version"]
        self.db.execute("UPDATE chapters SET read=1 WHERE _id=10")
        self.assertEqual(first_version, self.record()["state_version"])
        self.visit(duration=120)
        self.db.execute("UPDATE chapters SET read=0,last_page_read=0 WHERE _id=10")
        self.assertEqual(0, self.overview()[2])
        self.assertEqual(120, self.overview()[3])
        self.assertGreater(self.record()["state_version"], first_version)

    def test_partial_reading_and_progress_reset_preserve_reading_evidence(self):
        self.db.execute("UPDATE chapters SET last_page_read=3 WHERE _id=10")
        self.visit()
        self.assertEqual((1, 0, 0, 300), self.overview())
        self.db.execute("UPDATE chapters SET read=1 WHERE _id=10")
        self.db.execute(statement("setStateTrackingSuppressed"), {"suppressed": 1})
        self.db.execute("UPDATE chapters SET read=0,last_page_read=0 WHERE _id=10")
        self.db.execute(statement("setStateTrackingSuppressed"), {"suppressed": 0})
        self.assertEqual((1, 0, 1, 300), self.overview())
        self.assertEqual(0, self.db.execute("SELECT read FROM chapters WHERE _id=10").fetchone()[0])

    def test_history_clear_preserves_counts_time_and_cleanup_cannot_delete_it(self):
        self.db.execute("UPDATE chapters SET read=1 WHERE _id=10")
        self.visit()
        before = self.overview()
        self.db.execute(statement("removeAllHistory"))
        self.db.execute(statement("removeResettedHistory"))
        self.assertEqual(before, self.overview())
        self.assertEqual(1, self.record()["hidden"])
        self.assertEqual(1000, self.record()["last_read"])

    def test_clear_then_new_read_then_undo_never_overwrites_new_time(self):
        self.visit()
        visible = self.db.execute(statement("getVisibleHistoryIds")).fetchone()
        self.db.execute(statement("resetHistoryById"), {"historyId": visible[0]})
        hidden_version = visible[1] + 1
        self.visit(at=2000, duration=200)
        self.db.execute(statement("restoreVisibility"), {"historyId": visible[0], "hiddenVersion": hidden_version})
        self.assertEqual((2000, 500, 0), tuple(self.record()[k] for k in ("last_read", "time_read", "hidden")))

    def test_undo_hiding_restores_only_selected_operation(self):
        self.visit()
        visible = self.db.execute(statement("getVisibleHistoryIds")).fetchone()
        self.db.execute(statement("resetHistoryById"), {"historyId": visible[0]})
        self.db.execute(statement("restoreVisibility"), {"historyId": visible[0], "hiddenVersion": visible[1] + 1})
        self.assertEqual(0, self.record()["hidden"])
        self.assertEqual(1, self.record(20)["hidden"])
        self.assertEqual(300, self.overview()[3])

    def test_deleting_unread_chapter_does_not_complete_work(self):
        self.db.execute("UPDATE chapters SET read=1 WHERE _id=10")
        before = self.overview()
        self.db.execute("DELETE FROM chapters WHERE _id=20")
        self.assertEqual(before, self.overview())
        self.assertEqual(1, self.db.execute("SELECT count(*) FROM history WHERE chapter_id IS NULL").fetchone()[0])

    def test_deleting_entire_work_preserves_records_and_time(self):
        self.db.execute("UPDATE chapters SET read=1 WHERE manga_id=1")
        self.visit()
        before = self.overview()
        self.db.execute("DELETE FROM mangas WHERE _id=1")
        self.assertEqual(before, self.overview())
        self.assertEqual((1, 1, 2, 300), before)
        self.assertEqual(2, self.db.execute("SELECT count(*) FROM history WHERE chapter_id IS NULL").fetchone()[0])
        self.assertEqual([], self.db.execute("PRAGMA foreign_key_check").fetchall())

    def test_same_locator_reimport_reuses_record_without_adding_time(self):
        self.db.execute("UPDATE chapters SET read=1 WHERE _id=10")
        self.visit()
        key = self.record()["record_key"]
        self.db.execute("DELETE FROM chapters WHERE _id=10")
        add_chapter(self.db, 10, 1)
        self.assertEqual(key, self.record()["record_key"])
        self.assertEqual(2, self.db.execute("SELECT count(*) FROM history").fetchone()[0])
        self.assertEqual((1, 0, 1, 300), self.overview())

    def test_relocation_updates_locator_and_retains_record_key(self):
        key = self.record()["record_key"]
        self.visit()
        add_manga(self.db, 2, title="target", favorite=0)
        self.db.execute("UPDATE chapters SET manga_id=2,url='target/new.cbz' WHERE _id=10")
        self.assertEqual(key, self.record()["record_key"])
        self.assertEqual("target/new.cbz", self.record()["chapter_url"])
        self.assertEqual(2, self.db.execute("SELECT count(*) FROM reading_locator WHERE record_key=?", (key,)).fetchone()[0])
        self.assertEqual(300, self.overview()[3])

    def test_failed_source_transaction_rolls_back_detach_and_new_baseline(self):
        before = [tuple(r) for r in self.db.execute("SELECT * FROM history ORDER BY _id")]
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db:
                self.db.execute("DELETE FROM chapters WHERE _id=20")
                add_chapter(self.db, 30, 1)
                self.db.execute("INSERT INTO chapters(_id) VALUES(40)")
        self.assertEqual(before, [tuple(r) for r in self.db.execute("SELECT * FROM history ORDER BY _id")])

    def test_catalog_growth_reopens_completed_work(self):
        self.db.execute("UPDATE chapters SET read=1 WHERE manga_id=1")
        self.assertEqual(1, self.overview()[1])
        add_chapter(self.db, 30, 1)
        self.assertEqual((1, 0, 2, 0), self.overview())

    def test_late_attachment_projection_does_not_restore_cleared_sibling_progress(self):
        self.db.execute("UPDATE chapters SET read=1 WHERE _id=10")
        self.db.execute(statement("setStateTrackingSuppressed"), {"suppressed":1})
        self.db.execute("UPDATE chapters SET read=0,last_page_read=0 WHERE _id=10")
        add_chapter(self.db, 30, 1)
        self.db.execute("DELETE FROM reading_chapter_link WHERE chapter_id=30")
        self.db.execute("UPDATE chapters SET url='10' WHERE _id=30")
        self.db.execute(statement("attachPermanentRecords"))
        self.db.execute(statement("applyPermanentCompletionToAttachedChapter"), {"chapterId":30})
        self.db.execute(statement("setStateTrackingSuppressed"), {"suppressed":0})
        self.assertEqual(0, self.db.execute("SELECT read FROM chapters WHERE _id=10").fetchone()[0])
        self.assertEqual(1, self.db.execute("SELECT read FROM chapters WHERE _id=30").fetchone()[0])

    def test_restore_fresh_guard_protects_newer_unread_without_resetting_time(self):
        self.db.execute("UPDATE chapters SET read=1 WHERE _id=10")
        self.visit()
        self.db.execute("UPDATE chapters SET read=0,last_page_read=0 WHERE _id=10")
        self.db.execute(statement("setStateTrackingSuppressed"), {"suppressed":1})
        self.db.execute("UPDATE chapters SET read=1,last_page_read=22 WHERE _id=10")
        self.db.execute(statement("enforceExplicitUnreadByMangaId"), {"mangaId":1})
        self.db.execute(statement("setStateTrackingSuppressed"), {"suppressed":0})
        self.assertEqual((0, 0), tuple(self.db.execute("SELECT read,last_page_read FROM chapters WHERE _id=10").fetchone()))
        self.assertEqual((0, 300), (self.record()["completed"], self.record()["time_read"]))

    def test_known_library_scale(self):
        started = time.perf_counter()
        for work in range(2, 501):
            add_manga(self.db, work, favorite=work % 2)
            for offset in range(6):
                add_chapter(self.db, 10000 + work * 10 + offset, work, read=offset % 2)
        self.db.commit()
        self.assertEqual((499, 0, 1497, 0), self.overview())
        print(f"500 works / 2996 chapters: real SQLite setup + aggregation {(time.perf_counter() - started) * 1000:.0f} ms")


class ReadingMigrationSqlTest(unittest.TestCase):
    def legacy_database(self):
        # All other tables are unchanged by migration 30. A fixed legacy fixture also works
        # in CI shallow checkouts, where origin/main or older commits may not be present.
        db = create_database()
        self.addCleanup(db.close)
        for table in ("reading_chapter_link", "reading_locator", "reading_key_alias", "reading_state_context", "history"):
            db.execute(f"DROP TABLE {table}")
        legacy = ROOT / "app/src/test/resources/reading-statistics/legacy-history.sql"
        db.executescript(legacy.read_text(encoding="utf-8"))
        db.execute("CREATE VIEW historyView AS SELECT * FROM history")
        db.execute("PRAGMA foreign_keys=ON")
        return db

    def test_migration_preserves_hidden_duration_ids_and_unread_catalog(self):
        db = self.legacy_database()
        add_manga(db, 1, favorite=0)
        add_chapter(db, 10, 1, read=1)
        add_chapter(db, 20, 1)
        db.execute("INSERT INTO history(_id,chapter_id,last_read,time_read) VALUES(77,10,0,45000)")
        db.commit()
        migration = (SQL / "migrations/30.sqm").read_text(encoding="utf-8")
        execute_script_transactionally(db, migration)
        row = db.execute("SELECT * FROM history WHERE chapter_id=10").fetchone()
        self.assertEqual((77, 45000, 1, 1), tuple(row[k] for k in ("_id", "time_read", "completed", "hidden")))
        self.assertEqual((1, 0, 1, 45000), tuple(db.execute(statement("getReadingOverview")).fetchone()))
        db.execute("DELETE FROM mangas WHERE _id=1")
        self.assertEqual(45000, db.execute(statement("getReadDuration")).fetchone()[0])
        self.assertEqual([], db.execute("PRAGMA foreign_key_check").fetchall())

    def test_duplicate_legacy_locators_preserve_chapter_ids_without_double_count(self):
        db = self.legacy_database()
        add_manga(db, 1, favorite=0)
        add_chapter(db, 10, 1, read=1)
        add_chapter(db, 20, 1)
        db.execute("UPDATE chapters SET url='same.cbz' WHERE manga_id=1")
        db.execute("INSERT INTO history(_id,chapter_id,last_read,time_read) VALUES(77,10,100,120)")
        db.execute("INSERT INTO history(_id,chapter_id,last_read,time_read) VALUES(88,20,200,180)")
        db.commit()
        execute_script_transactionally(db, (SQL / "migrations/30.sqm").read_text(encoding="utf-8"))
        self.assertEqual([10, 20], [r[0] for r in db.execute("SELECT _id FROM chapters ORDER BY _id")])
        self.assertEqual(1, db.execute("SELECT count(*) FROM history").fetchone()[0])
        self.assertEqual(2, db.execute("SELECT count(*) FROM reading_chapter_link").fetchone()[0])
        self.assertEqual((1, 1, 1, 300), tuple(db.execute(statement("getReadingOverview")).fetchone()))
        db.execute(statement("upsert"), {"chapterId":20, "readAt":300, "time_read":40})
        self.assertEqual(340, db.execute(statement("getReadDuration")).fetchone()[0])
        db.execute("DELETE FROM chapters WHERE _id=10")
        db.execute(statement("upsert"), {"chapterId":20, "readAt":400, "time_read":60})
        self.assertEqual(400, db.execute(statement("getReadDuration")).fetchone()[0])
        db.execute("DELETE FROM mangas WHERE _id=1")
        self.assertEqual((1, 1, 1, 400), tuple(db.execute(statement("getReadingOverview")).fetchone()))
        self.assertEqual([], db.execute("PRAGMA foreign_key_check").fetchall())

    def test_duplicate_work_and_chapter_locators_migrate(self):
        db = self.legacy_database()
        for work in (1, 2):
            add_manga(db, work, favorite=0)
            add_chapter(db, work * 10, work, read=1)
        db.execute("UPDATE mangas SET url='same-work'")
        db.execute("UPDATE chapters SET url='same-work/one.cbz'")
        db.commit()
        execute_script_transactionally(db, (SQL / "migrations/30.sqm").read_text(encoding="utf-8"))
        self.assertEqual((1, 1, 1, 0), tuple(db.execute(statement("getReadingOverview")).fetchone()))
        self.assertEqual(2, db.execute("SELECT count(*) FROM chapters").fetchone()[0])
        self.assertEqual(1, db.execute("SELECT count(*) FROM reading_locator").fetchone()[0])
        self.assertEqual([], db.execute("PRAGMA foreign_key_check").fetchall())


if __name__ == "__main__":
    unittest.main(verbosity=2)
