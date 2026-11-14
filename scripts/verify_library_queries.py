import argparse
import pathlib
import re
import sqlite3
import statistics
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
SQL = ROOT / "data/src/main/sqldelight/tachiyomi"


def create_database():
    database = sqlite3.connect(":memory:", cached_statements=0)
    database.row_factory = sqlite3.Row
    for path in sorted((SQL / "data").glob("*.sq")):
        source = path.read_text(encoding="utf-8")
        source = re.sub(r"\b(INTEGER|TEXT|BLOB) AS \w+(?:<[^>]+>)?", r"\1", source)
        for statement in re.findall(r"CREATE TABLE .*?\n\);", source, re.S):
            database.executescript(statement)
        for statement in re.findall(r"CREATE (?:UNIQUE )?INDEX [^;]+;", source):
            database.executescript(statement)
    set_view(database, (SQL / "view/libraryView.sq").read_text(encoding="utf-8"))
    return database


def set_view(database, source):
    database.execute("DROP VIEW IF EXISTS libraryView")
    database.executescript(source.split("\nlibrary:")[0])


def add_manga(database, manga_id, title=None, favorite=1):
    database.execute(
        "INSERT INTO mangas(_id,source,url,title,status,favorite,initialized,viewer,"
        "chapter_flags,cover_last_modified,date_added) VALUES(?,1,?,?,0,?,1,0,0,0,0)",
        (manga_id, str(manga_id), title or str(manga_id), favorite),
    )


def add_chapter(database, chapter_id, manga_id, scanlator=None, read=0, bookmark=0, history=None):
    database.execute(
        "INSERT INTO chapters(_id,manga_id,url,name,scanlator,read,bookmark,last_page_read,"
        "chapter_number,source_order,date_fetch,date_upload) VALUES(?,?,?,?,?,?,?,0,0,0,?,?)",
        (chapter_id, manga_id, str(chapter_id), str(chapter_id), scanlator, read, bookmark,
         chapter_id * 10, chapter_id * 20),
    )
    if history is not None:
        database.execute(
            "INSERT INTO history(chapter_id,last_read,time_read) VALUES(?,?,0)",
            (chapter_id, history),
        )


def library_rows(database):
    return {row["_id"]: dict(row) for row in database.execute("SELECT * FROM libraryView")}


def assert_statistics(database, manga_id, expected):
    row = library_rows(database)[manga_id]
    actual = tuple(row[key] for key in (
        "totalCount", "readCount", "bookmarkCount", "latestUpload", "chapterFetchedAt", "lastRead",
    ))
    assert actual == expected, (manga_id, actual, expected)


def duplicate_ids(database, manga_id, title):
    source = (SQL / "data/mangas.sq").read_text(encoding="utf-8")
    query = re.search(r"getDuplicateLibraryManga:\s*(.*?)(?=^\w+:|\Z)", source, re.M | re.S)[1]
    return {row["_id"] for row in database.execute(query, {"id": manga_id, "title": title})}


def verify():
    with create_database() as database:
        for manga_id in range(1, 8):
            add_manga(database, manga_id, "normal" if manga_id == 2 else None, int(manga_id != 4))
        add_chapter(database, 21, 2, read=1, bookmark=1, history=20)
        add_chapter(database, 22, 2, "hidden", bookmark=1, history=999)
        add_chapter(database, 31, 3, "hidden", history=800)
        add_chapter(database, 41, 4, history=900)
        add_chapter(database, 51, 5, history=42)
        add_chapter(database, 61, 6)
        database.executemany(
            "INSERT INTO excluded_scanlators(manga_id,scanlator) VALUES(?,?)", [(2, "hidden"), (3, "hidden")],
        )
        database.executemany(
            "INSERT INTO mangas_categories(manga_id,category_id) VALUES(5,?)", [(1,), (2,)],
        )
        assert_statistics(database, 1, (0, 0, 0, 0, 0, 0))
        assert_statistics(database, 2, (1, 1, 1, 420, 210, 20))
        assert_statistics(database, 3, (0, 0, 0, 0, 0, 0))
        assert_statistics(database, 6, (1, 0, 0, 1220, 610, 0))
        assert 4 not in library_rows(database)
        assert set(library_rows(database)[5]["categories"].split(",")) == {"1", "2"}
        assert library_rows(database)[1]["categories"] == "0"
        add_chapter(database, 23, 2, history=500)
        assert_statistics(database, 2, (2, 1, 1, 460, 230, 500))
        database.execute("UPDATE chapters SET read=1,bookmark=1 WHERE _id=23")
        assert_statistics(database, 2, (2, 2, 2, 460, 230, 500))
        database.execute("UPDATE history SET last_read=0 WHERE chapter_id=23")
        assert library_rows(database)[2]["lastRead"] == 20
        database.execute("UPDATE history SET last_read=500 WHERE chapter_id=23")
        assert library_rows(database)[2]["lastRead"] == 500
        database.execute("DELETE FROM history WHERE chapter_id=23")
        database.execute("DELETE FROM chapters WHERE _id=23")
        assert_statistics(database, 2, (1, 1, 1, 420, 210, 20))
        database.execute("UPDATE mangas SET favorite=0 WHERE _id=2")
        assert 2 not in library_rows(database)
        database.execute("UPDATE mangas SET favorite=1 WHERE _id=2")
        assert library_rows(database)[2]["lastRead"] == 20
        assert duplicate_ids(database, 1, "") == set()
        assert duplicate_ids(database, 1, "   ") == set()
        assert duplicate_ids(database, 1, "normal") == {2}
        for manga_id in (1, 3):
            database.execute(
                "INSERT INTO manga_sync(manga_id,sync_id,remote_id,title,last_chapter_read,"
                "total_chapters,status,score,remote_url,start_date,finish_date) "
                "VALUES(?,1,10,'tracked',0,0,0,0,'',0,0)", (manga_id,),
            )
        assert duplicate_ids(database, 1, "") == {3}
        assert duplicate_ids(database, 1, "   ") == {3}
    print("PASS: library statistics, exclusions, categories, updates, history restore, favorites and duplicate queries")


def benchmark(baseline, favorite_count=3000, history_stride=4):
    with create_database() as database:
        for manga_id in range(1, 6001):
            add_manga(database, manga_id, favorite=int(manga_id <= favorite_count))
        database.executemany(
            "INSERT INTO chapters(_id,manga_id,url,name,scanlator,read,bookmark,last_page_read,"
            "chapter_number,source_order,date_fetch,date_upload) VALUES(?,?,?,?,?,0,0,0,0,0,?,?)",
            ((chapter_id, (chapter_id - 1) // 100 + 1, str(chapter_id), str(chapter_id),
              "hidden" if chapter_id % 7 == 0 else None, chapter_id, chapter_id)
             for chapter_id in range(1, 600001)),
        )
        database.executemany(
            "INSERT INTO history(chapter_id,last_read,time_read) VALUES(?,?,0)",
            ((chapter_id, chapter_id) for chapter_id in range(history_stride, 600001, history_stride)),
        )
        database.executemany(
            "INSERT INTO excluded_scanlators(manga_id,scanlator) VALUES(?,'hidden')",
            ((manga_id,) for manga_id in range(10, 6001, 10)),
        )
        database.commit()
        current = (SQL / "view/libraryView.sq").read_text(encoding="utf-8")
        results = {}
        for label, view in (("baseline", baseline.read_text(encoding="utf-8")), ("current", current)):
            set_view(database, view)
            rows = library_rows(database)
            durations = []
            for iteration in range(5):
                started = time.perf_counter()
                library_rows(database)
                durations.append((time.perf_counter() - started) * 1000)
            results[label] = (rows, statistics.median(durations))
            print(f"{label}: {len(rows)} library manga, median={results[label][1]:.1f} ms")
            for row in database.execute("EXPLAIN QUERY PLAN SELECT * FROM libraryView"):
                print("  " + row["detail"])
        assert results["baseline"][0] == results["current"][0], "Library query semantics changed"
        print(f"PASS: identical rows for 6000 manga / 600000 chapters / {600000 // history_stride} history rows")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline-view", type=pathlib.Path)
    parser.add_argument("--favorite-count", type=int, choices=(3000, 6000), default=3000)
    parser.add_argument("--history-stride", type=int, choices=(1, 4, 600001), default=4)
    arguments = parser.parse_args()
    verify()
    if arguments.baseline_view:
        benchmark(arguments.baseline_view, arguments.favorite_count, arguments.history_stride)
