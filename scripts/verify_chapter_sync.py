import re
import sqlite3
import unittest

from verify_library_queries import ROOT, add_chapter, add_manga, create_database

SOURCE = (ROOT / "data/src/main/sqldelight/tachiyomi/data/chapters.sq").read_text(encoding="utf-8")


def query(name):
    statement = re.search(rf"^{name}:\s*(.*?)(?=^\w+:|\Z)", SOURCE, re.M | re.S)[1]
    return statement.split(";", 1)[0]


def source_fields(chapter_id, **fields):
    parameters = dict.fromkeys(re.findall(r":(\w+)", query("updateSourceFields")))
    parameters.update(chapterId=chapter_id, **fields)
    return parameters


def new_chapter(url, **fields):
    parameters = dict(
        mangaId=1, url=url, name=url, scanlator=None, read=0, bookmark=0,
        lastPageRead=0, totalPages=0, customOrder=0, chapterNumber=1,
        sourceOrder=0, dateFetch=123, dateUpload=456, version=1,
        memo=b"{}", translatedName=None, markedReadAt=0,
    )
    parameters.update(fields)
    return parameters


class SourceSyncSqliteTest(unittest.TestCase):
    def setUp(self):
        self.database = create_database()
        self.addCleanup(self.database.close)
        self.database.execute("PRAGMA foreign_keys = ON")
        for table in ("chapters", "good_doujins"):
            source = (ROOT / f"data/src/main/sqldelight/tachiyomi/data/{table}.sq").read_text(encoding="utf-8")
            for trigger in re.findall(r"CREATE TRIGGER .*?\nEND;", source, re.S):
                self.database.executescript(trigger)
        add_manga(self.database, 1)
        add_manga(self.database, 2)
        add_chapter(self.database, 10, 1, "old", history=777)
        add_chapter(self.database, 20, 1, history=888)
        add_chapter(self.database, 30, 2, history=999)
        self.database.execute("INSERT INTO good_doujins VALUES(10,1,555)")
        self.database.execute("INSERT INTO good_doujins VALUES(20,1,666)")
        self.database.commit()

    def snapshot(self):
        return {
            table: [tuple(row) for row in self.database.execute(f"SELECT * FROM {table} ORDER BY rowid")]
            for table in ("chapters", "mangas", "history", "good_doujins")
        }

    def test_remote_update_retains_latest_user_fields_and_identity(self):
        self.database.execute(
            "UPDATE chapters SET read=1,bookmark=1,last_page_read=6,total_pages=10,"
            "translated_name='Translated',custom_order=9,marked_read_at=4321 WHERE _id=10",
        )
        before = dict(self.database.execute("SELECT * FROM chapters WHERE _id=10").fetchone())
        self.database.execute(query("updateSourceFields"), source_fields(
            10, name="Remote title", scanlator=None, chapterNumber=8, sourceOrder=4,
            dateUpload=1234, totalPages=50, memo=b'{"remote":true}',
        ))
        after = dict(self.database.execute("SELECT * FROM chapters WHERE _id=10").fetchone())
        remote_columns = {"name", "scanlator", "chapter_number", "source_order", "date_upload", "total_pages", "memo"}
        for column in before.keys() - remote_columns - {"last_modified_at"}:
            self.assertEqual(before[column], after[column], column)
        self.assertEqual(6, after["last_page_read"])
        self.assertIsNone(after["scanlator"])
        self.assertEqual(50, after["total_pages"])
        self.assertEqual("Remote title", after["name"])
        self.assertEqual(777, self.database.execute("SELECT last_read FROM history WHERE chapter_id=10").fetchone()[0])
        self.assertEqual(555, self.database.execute("SELECT marked_at FROM good_doujins WHERE chapter_id=10").fetchone()[0])

    def test_late_update_failure_rolls_back_deletion_insert_and_cascades(self):
        self.database.executescript(
            "CREATE TRIGGER reject_source_update BEFORE UPDATE OF name ON chapters "
            "WHEN new.name='Rejected' BEGIN SELECT RAISE(ABORT,'source update failed'); END;",
        )
        before = self.snapshot()
        with self.assertRaisesRegex(sqlite3.IntegrityError, "source update failed"):
            with self.database:
                self.database.execute(query("removeChaptersWithIds").replace(":chapterIds", "(20)"))
                self.database.execute(query("insertReturningId"), new_chapter("added")).fetchone()
                self.database.execute(query("updateSourceFields"), source_fields(10, name="Rejected"))
        self.assertEqual(before, self.snapshot())

    def test_insert_failure_rolls_back_prior_deletion(self):
        before = self.snapshot()
        with self.assertRaises(sqlite3.IntegrityError):
            with self.database:
                self.database.execute(query("removeChaptersWithIds").replace(":chapterIds", "(20)"))
                self.database.execute(query("insertReturningId"), new_chapter("invalid", mangaId=999)).fetchone()
        self.assertEqual(before, self.snapshot())

    def test_interruption_rolls_back_all_completed_statements(self):
        class Interrupted(BaseException):
            pass

        before = self.snapshot()
        with self.assertRaises(Interrupted):
            with self.database:
                self.database.execute(query("removeChaptersWithIds").replace(":chapterIds", "(20)"))
                self.database.execute(query("insertReturningId"), new_chapter("added")).fetchone()
                self.database.execute(query("updateSourceFields"), source_fields(10, name="Updated"))
                raise Interrupted()
        self.assertEqual(before, self.snapshot())

    def test_confirmed_delete_commits_cascades_and_retains_unrelated_rows(self):
        with self.database:
            self.database.execute(query("removeChaptersWithIds").replace(":chapterIds", "(20)"))
        self.assertEqual([10, 30], [row[0] for row in self.database.execute("SELECT _id FROM chapters ORDER BY _id")])
        self.assertEqual([10, 30], [row[0] for row in self.database.execute("SELECT chapter_id FROM history ORDER BY chapter_id")])
        self.assertEqual([10], [row[0] for row in self.database.execute("SELECT chapter_id FROM good_doujins")])

    def test_large_library_mixed_sync_preserves_unrelated_records_and_user_fields(self):
        for manga_id in range(3, 3003):
            add_manga(self.database, manga_id)
            for offset in range(4):
                chapter_id = 100000 + manga_id * 4 + offset
                add_chapter(
                    self.database, chapter_id, manga_id, read=offset % 2,
                    bookmark=offset % 2, history=chapter_id,
                )
            if manga_id % 300 == 0:
                self.database.execute(
                    "INSERT INTO good_doujins VALUES(?,?,?)", (100000 + manga_id * 4, manga_id, manga_id),
                )
        target_ids = list(range(20000, 22400))
        for chapter_id in target_ids:
            add_chapter(self.database, chapter_id, 1, "old", history=chapter_id)
        self.database.execute("UPDATE chapters SET url='added/0' WHERE _id=30")
        self.database.execute(
            "UPDATE chapters SET read=(_id % 2),bookmark=1,last_page_read=6,total_pages=10,"
            "translated_name='Translated ' || _id,custom_order=_id,marked_read_at=1000+_id WHERE manga_id=1",
        )
        self.database.commit()
        self.assertEqual(3002, self.database.execute("SELECT count(*) FROM mangas").fetchone()[0])
        self.assertEqual(14403, self.database.execute("SELECT count(*) FROM chapters").fetchone()[0])

        def unrelated_records():
            return {
                "mangas": [tuple(row) for row in self.database.execute("SELECT * FROM mangas WHERE _id!=1 ORDER BY _id")],
                "chapters": [tuple(row) for row in self.database.execute(
                    "SELECT * FROM chapters WHERE manga_id!=1 ORDER BY _id",
                )],
                "history": [tuple(row) for row in self.database.execute(
                    "SELECT H.* FROM history H JOIN chapters C ON C._id=H.chapter_id "
                    "WHERE C.manga_id!=1 ORDER BY H._id",
                )],
                "marks": [tuple(row) for row in self.database.execute(
                    "SELECT * FROM good_doujins WHERE manga_id!=1 ORDER BY chapter_id",
                )],
            }

        unrelated_before = unrelated_records()
        updated_ids = [10] + target_ids[:1200]
        removed_ids = [20] + target_ids[1200:]
        updated_before = {
            chapter_id: dict(self.database.execute("SELECT * FROM chapters WHERE _id=?", (chapter_id,)).fetchone())
            for chapter_id in updated_ids
        }
        history_before = [tuple(row) for row in self.database.execute(
            "SELECT * FROM history WHERE chapter_id IN (" + ",".join("?" for _ in updated_ids) + ") ORDER BY _id",
            updated_ids,
        )]
        marks_before = [tuple(row) for row in self.database.execute("SELECT * FROM good_doujins WHERE chapter_id=10")]
        added_urls = [f"added/{index}" for index in range(1201)]
        candidates = added_urls + added_urls[:3] + ["10", str(updated_ids[1])]
        inserted_ids = []
        lookup = query("getChapterByUrlAndMangaId")
        insert = query("insertReturningId")
        with self.database:
            self.database.execute(
                query("removeChaptersWithIds").replace(":chapterIds", "(" + ",".join("?" for _ in removed_ids) + ")"),
                removed_ids,
            )
            for url in candidates:
                existing = self.database.execute(lookup, {"chapterUrl": url, "mangaId": 1}).fetchone()
                if existing is None:
                    inserted_ids.append(self.database.execute(insert, new_chapter(url)).fetchone()[0])
            self.database.executemany(query("updateSourceFields"), (
                source_fields(
                    chapter_id, name=f"Remote {chapter_id}", scanlator="new", chapterNumber=index,
                    sourceOrder=index, dateUpload=123456, totalPages=50, memo=b'{"remote":true}',
                )
                for index, chapter_id in enumerate(updated_ids)
            ))
            self.assertTrue(self.database.in_transaction)
        self.assertFalse(self.database.in_transaction)

        self.assertEqual(1201, len(set(inserted_ids)))
        self.assertEqual(14403, self.database.execute("SELECT count(*) FROM chapters").fetchone()[0])
        self.assertEqual(unrelated_before, unrelated_records())
        self.assertEqual(set(updated_ids) | set(inserted_ids), {
            row[0] for row in self.database.execute("SELECT _id FROM chapters WHERE manga_id=1")
        })
        self.assertEqual(set(added_urls), {
            row[0] for row in self.database.execute("SELECT url FROM chapters WHERE manga_id=1 AND url LIKE 'added/%'")
        })
        remote_columns = {"name", "scanlator", "chapter_number", "source_order", "date_upload", "total_pages", "memo"}
        for chapter_id, before in updated_before.items():
            after = dict(self.database.execute("SELECT * FROM chapters WHERE _id=?", (chapter_id,)).fetchone())
            for column in before.keys() - remote_columns - {"last_modified_at"}:
                self.assertEqual(before[column], after[column], (chapter_id, column))
            self.assertEqual(f"Remote {chapter_id}", after["name"])
            self.assertEqual(50, after["total_pages"])
        self.assertEqual(history_before, [tuple(row) for row in self.database.execute(
            "SELECT * FROM history WHERE chapter_id IN (" + ",".join("?" for _ in updated_ids) + ") ORDER BY _id",
            updated_ids,
        )])
        self.assertEqual(marks_before, [tuple(row) for row in self.database.execute(
            "SELECT * FROM good_doujins WHERE chapter_id=10",
        )])
        self.assertEqual(0, self.database.execute(
            "SELECT count(*) FROM history WHERE chapter_id IN (" + ",".join("?" for _ in removed_ids) + ")",
            removed_ids,
        ).fetchone()[0])
        self.assertIsNone(self.database.execute("SELECT * FROM good_doujins WHERE chapter_id=20").fetchone())

    def test_existing_url_lookup_returns_stable_id_before_insertion(self):
        with self.database:
            inserted = self.database.execute(query("insertReturningId"), new_chapter("added")).fetchone()[0]
            existing = self.database.execute(
                query("getChapterByUrlAndMangaId"), {"chapterUrl": "added", "mangaId": 1},
            ).fetchone()
            self.assertEqual(inserted, existing["_id"])
            self.assertEqual(1, self.database.execute("SELECT count(*) FROM chapters WHERE url='added'").fetchone()[0])


if __name__ == "__main__":
    unittest.main(verbosity=2)
