package tachiyomi.core.common.util.lang

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.util.lang.SearchTextNormalizer.containsSearch

class SearchTextNormalizerTest {

    // ------------------------------------------------------------------
    // Simplified / traditional
    // ------------------------------------------------------------------

    @Test
    fun `simplified query matches traditional title`() {
        assertTrue("進擊的巨人".containsSearch("进击"))
    }

    @Test
    fun `traditional query matches simplified title`() {
        assertTrue("进击的巨人".containsSearch("進擊"))
    }

    // ------------------------------------------------------------------
    // Width folding
    // ------------------------------------------------------------------

    @Test
    fun `full-width title matches half-width query`() {
        assertTrue("ＡＢＣ：１２３".containsSearch("abc:12"))
    }

    @Test
    fun `half-width title matches full-width query`() {
        assertTrue("Re:Zero".containsSearch("ｒｅ：ｚｅｒｏ"))
    }

    @Test
    fun `whitespace and zero width characters are ignored`() {
        assertTrue("One  Piece​".containsSearch("one piece"))
        assertTrue("  Ｏｎｅ　Ｐｉｅｃｅ  ".containsSearch("one piece"))
    }

    // ------------------------------------------------------------------
    // Japanese shinjitai / kyujitai
    // ------------------------------------------------------------------

    @Test
    fun `shinjitai and kyujitai reach each other`() {
        assertTrue("桜".containsSearch("櫻"))
        assertTrue("櫻".containsSearch("桜"))
    }

    @Test
    fun `shinjitai query matches a full japanese title`() {
        assertTrue("東京喰種".containsSearch("東京喰種"))
    }

    @Test
    fun `japanese and chinese forms of the same character match`() {
        // 転 -> 轉 -> 转, so a simplified query reaches the shinjitai title.
        assertTrue("転生したらスライムだった件".containsSearch("转生"))
    }

    @Test
    fun `shinjitai folding handles the common joyo set`() {
        for ((shinji, kyuji) in listOf("桜" to "櫻", "沢" to "澤", "竜" to "龍", "悪" to "惡")) {
            assertEquals(
                SearchTextNormalizer.normalize(kyuji),
                SearchTextNormalizer.normalize(shinji),
                "%s and %s should normalize alike".format(shinji, kyuji),
            )
        }
    }

    // ------------------------------------------------------------------
    // Kana
    // ------------------------------------------------------------------

    @Test
    fun `katakana and hiragana match each other`() {
        assertTrue("ワンピース".containsSearch("わんぴーす"))
        assertTrue("わんぴーす".containsSearch("ワンピース"))
    }

    @Test
    fun `kana normalization is stable`() {
        val normalized = SearchTextNormalizer.normalize("ワンピース")
        assertEquals(normalized, SearchTextNormalizer.normalize("わんぴーす"))
    }

    // ------------------------------------------------------------------
    // Pinyin
    // ------------------------------------------------------------------

    @Test
    fun `pinyin query matches chinese title`() {
        assertTrue("进击的巨人".containsSearch("jinji"))
        assertTrue("进击的巨人".containsSearch("juren"))
    }

    @Test
    fun `pinyin query does not need to start at the beginning`() {
        assertTrue("关于我转生变成史莱姆这档事".containsSearch("zhuan"))
    }

    @Test
    fun `pinyin initials are supported`() {
        assertTrue("进击的巨人".containsSearch("jj"))
    }

    @Test
    fun `pinyin works on traditional and shinjitai titles`() {
        // 進 -> 进 -> jin
        assertTrue("進擊的巨人".containsSearch("jinji"))
        // 桜 -> 櫻 -> 樱 -> ying
        assertTrue("桜".containsSearch("ying"))
    }

    @Test
    fun `pinyin handles polyphonic characters`() {
        // 重 is both zhong and chong; neither reading may be dropped.
        assertTrue("重庆".containsSearch("chongqing"))
        assertTrue("重庆".containsSearch("zhongqing"))
    }

    @Test
    fun `pinyin honours spacing in the query`() {
        assertTrue("进击的巨人".containsSearch("jin ji"))
    }

    @Test
    fun `pinyin does not match unrelated titles`() {
        assertFalse("进击的巨人".containsSearch("dazhao"))
        assertFalse("进击的巨人".containsSearch("attack on titan"))
    }

    @Test
    fun `single letter queries are ignored`() {
        // Below the threshold: one letter matches far too much to be useful.
        assertFalse("进击的巨人".containsSearch("j"))
    }

    @Test
    fun `english query still does not leak into pinyin`() {
        assertFalse("进击的巨人".containsSearch("titan"))
    }

    // ------------------------------------------------------------------
    // Baseline behaviour that must not regress
    // ------------------------------------------------------------------

    @Test
    fun `ascii matching is unaffected`() {
        assertTrue("Attack on Titan".containsSearch("attack"))
        assertFalse("Attack on Titan".containsSearch("attack on titan 2"))
        assertFalse("Berserk".containsSearch("Claymore"))
    }

    @Test
    fun `unrelated cjk titles do not match`() {
        assertFalse("進擊的巨人".containsSearch("東京"))
    }

    @Test
    fun `normalize folds case width whitespace and script`() {
        assertEquals("进击的巨人", SearchTextNormalizer.normalize("進擊的巨人"))
        assertEquals("abc:123", SearchTextNormalizer.normalize("  ＡＢＣ：１２３  "))
        assertEquals("a b", SearchTextNormalizer.normalize("A　  B"))
    }

    @Test
    fun `empty query matches everything`() {
        assertTrue("Anything".containsSearch(""))
        assertTrue("".containsSearch(""))
        assertFalse("".containsSearch("x"))
    }
}
