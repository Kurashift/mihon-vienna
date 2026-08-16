package tachiyomi.core.common.util.lang

import java.text.Collator
import java.util.Locale

private val collator by lazy {
    val locale = Locale.getDefault()
    Collator.getInstance(locale).apply {
        strength = Collator.PRIMARY
    }
}

/**
 * Collator-based comparison for names. Names that start with a digit sort after names that
 * start with a letter or CJK character, so digit-leading titles stay behind English/Chinese/
 * Japanese names in the library and updates lists too.
 */
fun String.compareToWithCollator(other: String): Int {
    val thisStartsWithDigit = firstOrNull()?.isDigit() == true
    val otherStartsWithDigit = other.firstOrNull()?.isDigit() == true
    if (thisStartsWithDigit != otherStartsWithDigit) {
        return if (thisStartsWithDigit) 1 else -1
    }
    return collator.compare(this, other)
}
