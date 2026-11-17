import argparse
import platform
import sqlite3
import statistics
import time

from verify_library_queries import add_manga, create_database


def benchmark(works, chapters_per_work, iterations):
    database = create_database()
    try:
        chapters = works * chapters_per_work
        for manga_id in range(1, works + 1):
            add_manga(database, manga_id)
        database.executemany(
            "INSERT INTO chapters(_id,manga_id,url,name,read,bookmark,last_page_read,"
            "chapter_number,source_order,date_fetch,date_upload) VALUES(?,?,?,?,?,?,0,0,0,?,?)",
            ((chapter_id, (chapter_id - 1) // chapters_per_work + 1, str(chapter_id),
              str(chapter_id), int(chapter_id % 4 == 0), int(chapter_id % 13 == 0),
              chapter_id, chapter_id) for chapter_id in range(1, chapters + 1)),
        )
        database.executemany(
            "INSERT INTO history(chapter_id,last_read,time_read) VALUES(?,?,0)",
            ((chapter_id, chapter_id) for chapter_id in range(4, chapters + 1, 4)),
        )
        database.executemany(
            "INSERT INTO mangas_categories(manga_id,category_id) VALUES(?,?)",
            ((manga_id, manga_id % 8 + 1) for manga_id in range(1, works + 1)),
        )
        database.executemany(
            "INSERT INTO excluded_scanlators(manga_id,scanlator) VALUES(?,'hidden')",
            ((manga_id,) for manga_id in range(10, works + 1, 10)),
        )
        database.commit()
        print(f"Python {platform.python_version()}, SQLite {sqlite3.sqlite_version}, {platform.machine()}")
        print(f"Fixture: {works} favorites, {chapters} chapters, {chapters // 4} history rows")
        print("In-memory host SQL only; excludes Android launch, Kotlin mapping and UI drawing.")

        def read_library():
            return [tuple(row) for row in database.execute("SELECT * FROM libraryView")]

        started = time.perf_counter()
        expected = read_library()
        print(f"First SQL result: {(time.perf_counter() - started) * 1000:.1f} ms")
        assert len(expected) == works
        for label, mutation in (
            ("unchanged", None),
            ("progress", "UPDATE chapters SET read=1,last_page_read=7 WHERE _id=1"),
            ("batch_read", "UPDATE chapters SET read=1 WHERE _id<=1201"),
        ):
            if mutation:
                with database:
                    database.execute(mutation)
                expected = read_library()
            timings = {1: [], 2: []}
            for iteration in range(iterations):
                for subscriptions in ((2, 1) if iteration % 2 == 0 else (1, 2)):
                    started = time.perf_counter()
                    results = [read_library() for _ in range(subscriptions)]
                    elapsed = (time.perf_counter() - started) * 1000
                    assert all(rows == expected for rows in results)
                    timings[subscriptions].append(elapsed)
            before = statistics.median(timings[2])
            after = statistics.median(timings[1])
            print(f"{label}: two queries={before:.1f} ms, one query={after:.1f} ms, "
                  f"saved={before - after:.1f} ms (median of {iterations}, alternating order)")
        print("PASS: every query returned identical rows within each scenario")
    finally:
        database.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Measure duplicate library SQL subscription cost on a synthetic host database")
    parser.add_argument("--works", type=int, default=500)
    parser.add_argument("--chapters-per-work", type=int, default=6)
    parser.add_argument("--iterations", type=int, default=31)
    arguments = parser.parse_args()
    if min(arguments.works, arguments.chapters_per_work, arguments.iterations) < 1:
        parser.error("all counts must be positive")
    benchmark(arguments.works, arguments.chapters_per_work, arguments.iterations)
