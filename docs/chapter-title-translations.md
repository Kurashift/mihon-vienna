# Local chapter title translations

Local chapters keep their original source title. An optional translated title is stored on the
same chapter database row, so an in-app chapter move also carries the translation, reading
progress, and custom cover together.

## Display and sorting

- `Source title` always shows the original chapter name.
- `Translated title` shows the translated title and falls back to the original when empty.
- The original title remains visible below a translated title and is the value copied by the
  existing title-copy gesture.
- `Translated title` sorting uses the displayed translated title, then the original title, then
  the chapter database ID for deterministic ordering.

## Import and export

The manga chapter settings display tab exports one JSON file per manga. Each entry contains:

- `chapterId`: the primary in-app identity;
- `originalTitle`: the untouched source title;
- `originalUrl`: the exact local folder/file URL used as a recovery key;
- `translatedTitle`: the editable translated title.

Import first matches the chapter ID while checking its original title or URL. If database IDs
changed, it accepts an exact URL match. It deliberately does not match by title alone because
different author folders can contain works with the same filename. Blank and unmatched entries
are ignored, so a partial translation file does not erase existing titles or affect new works.

The database migration adds a nullable `translated_name` column to `chapters`. Existing rows
remain unchanged, and translated titles are included in normal app backups.
