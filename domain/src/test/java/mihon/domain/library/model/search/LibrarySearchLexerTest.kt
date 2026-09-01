package mihon.domain.library.model.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LibrarySearchLexerTest {

    @Test
    fun `ascii query splits on whitespace and comma`() {
        assertEquals(
            listOf(
                LibrarySearchLexer.Token.General("one"),
                LibrarySearchLexer.Token.General("two"),
                LibrarySearchLexer.Token.General("three"),
            ),
            LibrarySearchLexer.tokenize("one two,three"),
        )
    }

    @Test
    fun `cjk term is kept as a single token`() {
        assertEquals(
            listOf(LibrarySearchLexer.Token.General("進擊的巨人")),
            LibrarySearchLexer.tokenize("進擊的巨人"),
        )
    }

    @Test
    fun `full width comma and enumeration comma separate cjk terms`() {
        assertEquals(
            listOf(
                LibrarySearchLexer.Token.General("東京"),
                LibrarySearchLexer.Token.General("喰種"),
                LibrarySearchLexer.Token.General("巨人"),
            ),
            LibrarySearchLexer.tokenize("東京，喰種、巨人"),
        )
    }

    @Test
    fun `ideographic space separates cjk terms`() {
        assertEquals(
            listOf(
                LibrarySearchLexer.Token.General("東京"),
                LibrarySearchLexer.Token.General("喰種"),
            ),
            LibrarySearchLexer.tokenize("東京　喰種"),
        )
    }

    @Test
    fun `quoted phrase keeps its punctuation`() {
        assertEquals(
            listOf(LibrarySearchLexer.Token.General("東京，喰種")),
            LibrarySearchLexer.tokenize("\"東京，喰種\""),
        )
    }

    @Test
    fun `operators and fields keep working alongside cjk`() {
        assertEquals(
            listOf(
                LibrarySearchLexer.Token.General("巨人"),
                LibrarySearchLexer.Token.Or,
                LibrarySearchLexer.Token.Field("title", "東京"),
            ),
            LibrarySearchLexer.tokenize("巨人 || title:東京"),
        )
    }
}
