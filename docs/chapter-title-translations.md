# Local chapter title translations

Local chapters keep their original source title. An optional translated title is stored on the
same chapter database row, so an in-app chapter move also carries the translation, reading
progress, and custom cover together.

## Display and sorting

- `Source title` always shows the original chapter name.
- `Translated title` shows the translated title and falls back to the original when empty.
- The original title remains visible below a translated title and is the value copied by the
  existing title-copy gesture.
- In translated-title mode, long-pressing a local chapter opens actions for editing its translated
  title, copying the original title, or selecting the chapter. Saving a blank translated title
  restores the original-title fallback.
- `Translated title` sorting uses the displayed translated title, then the original title, then
  the chapter database ID for deterministic ordering.

## Import and export

The manga detail overflow menu opens a dialog for exporting or importing the current manga.
`More > Data and storage > Local library chapter translations` manages the entire local library.
It can export or import one whole-library JSON file, or batch-import multiple JSON files exported
from individual manga detail pages. Each chapter entry contains:

- `chapterId`: the primary in-app identity;
- `originalTitle`: the untouched source title;
- `originalUrl`: the exact local folder/file URL used as a recovery key;
- `translatedTitle`: the editable translated title.

Import first matches the chapter ID while checking its original title or URL. If database IDs
changed, it accepts an exact URL match. It deliberately does not match by title alone because
different author folders can contain works with the same filename. Blank and unmatched entries
are ignored, so a partial translation file does not erase existing titles or affect new works.
An untranslated chapter displays its original title automatically.

Whole-library operations enumerate the current local-source filesystem snapshot. Historical
database manga rows, removed chapter rows, cover images, and other unsupported files are not
included in export counts.

The database migration adds a nullable `translated_name` column to `chapters`. Existing rows
remain unchanged, and translated titles are included in normal app backups.
