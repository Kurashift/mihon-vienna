import re
import sqlite3
import unittest

from verify_library_queries import ROOT, add_chapter, add_manga, create_database


def query(table, name):
    source = (ROOT / f"data/src/main/sqldelight/tachiyomi/data/{table}.sq").read_text(encoding="utf-8")
    statement = re.search(rf"^{name}:\s*(.*?)(?=^\w+:|\Z)", source, re.M | re.S)[1]
    return statement.split(";", 1)[0]


class BackupSqlIntegrityTest(unittest.TestCase):
    def setUp(self):
        self.database = create_database()
        self.addCleanup(self.database.close)
        self.database.execute("PRAGMA foreign_keys = ON")
        add_manga(self.database, 9001)
        self.database.execute("UPDATE mangas SET source=0 WHERE _id=9001")
        add_chapter(self.database, 9002, 9001, history=12000)
        self.database.execute(
            "UPDATE chapters SET bookmark=1,last_page_read=5,total_pages=22,custom_order=17,"
            "translated_name='translated',memo=? WHERE _id=9002",
            (b'{"mihon.pageCount":22}',),
        )
        self.database.commit()

    def update(self, **values):
        statement = query("chapters", "update")
        parameters = dict.fromkeys(re.findall(r":(\w+)", statement))
        parameters.update(chapterId=9002, **values)
        self.database.execute(statement, parameters)
        return self.database.execute("SELECT * FROM chapters WHERE _id=9002").fetchone()

    def test_legacy_defaults_reset_order_and_memo_but_null_translation_is_retained(self):
        row = self.update(customOrder=0, translatedName=None, memo=b"{}", totalPages=22, lastPageRead=5)
        self.assertEqual(0, row["custom_order"])
        self.assertEqual("translated", row["translated_name"])
        self.assertEqual(b"{}", row["memo"])
        self.assertEqual(9002, row["_id"])
        self.assertEqual(9001, row["manga_id"])

    def test_current_restore_parameters_preserve_user_fields_and_progress(self):
        row = self.update(
            read=0, bookmark=1, lastPageRead=5, totalPages=22, customOrder=9,
            translatedName="restored", memo=b'{"mihon.pageCount":22}', markedReadAt=1234,
        )
        self.assertEqual((5, 22, 9, "restored", 1, 1234), tuple(row[key] for key in (
            "last_page_read", "total_pages", "custom_order", "translated_name", "bookmark", "marked_read_at",
        )))

    def test_history_merge_parameters_are_idempotent_and_target_new_chapter_id(self):
        self.database.execute("UPDATE history SET time_read=300 WHERE chapter_id=9002")
        statement = query("history", "upsert")
        self.database.execute(statement, {"chapterId": 9002, "readAt": 12000, "time_read": 200})
        self.database.execute(statement, {"chapterId": 9002, "readAt": 12000, "time_read": 0})
        row = self.database.execute("SELECT chapter_id,last_read,time_read FROM history").fetchone()
        self.assertEqual((9002, 12000, 500), tuple(row))

    def test_good_mark_accepts_remapped_local_identity_and_rejects_wrong_ownership(self):
        statement = query("good_doujins", "upsert")
        self.database.execute(statement, {"chapterId": 9002, "mangaId": 9001, "markedAt": 123})
        add_manga(self.database, 8001)
        self.database.execute(statement, {"chapterId": 9002, "mangaId": 8001, "markedAt": 456})
        row = self.database.execute("SELECT chapter_id,manga_id,marked_at FROM good_doujins").fetchone()
        self.assertEqual((9002, 9001, 123), tuple(row))

    def test_later_failure_rolls_back_restore_updates_history_and_marks(self):
        before = tuple(self.database.execute("SELECT * FROM chapters WHERE _id=9002").fetchone())
        with self.assertRaises(sqlite3.IntegrityError):
            with self.database:
                self.update(customOrder=9, translatedName="restored")
                self.database.execute(query("good_doujins", "upsert"), {
                    "chapterId": 9002, "mangaId": 9001, "markedAt": 123,
                })
                self.database.execute("INSERT INTO history(chapter_id,last_read,time_read) VALUES(-1,0,0)")
        after = tuple(self.database.execute("SELECT * FROM chapters WHERE _id=9002").fetchone())
        self.assertEqual(before, after)
        self.assertEqual(0, self.database.execute("SELECT count(*) FROM good_doujins").fetchone()[0])


if __name__ == "__main__":
    unittest.main()
