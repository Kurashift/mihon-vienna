package tachiyomi.core.common.util.system

/**
 * The image formats the app reads and writes, with the extension each one is stored under.
 *
 * Deliberately kept out of [ImageUtil]: that object reads display metrics at class-init time, so
 * anything reachable only through it cannot be exercised by a plain JVM unit test. The page rule
 * in [PageImageName] needs exactly this table, so it lives here.
 */
enum class ImageType(val mime: String, val extension: String) {
    AVIF("image/avif", "avif"),
    GIF("image/gif", "gif"),
    HEIF("image/heif", "heif"),
    JPEG("image/jpeg", "jpg"),
    JXL("image/jxl", "jxl"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp"),
}
