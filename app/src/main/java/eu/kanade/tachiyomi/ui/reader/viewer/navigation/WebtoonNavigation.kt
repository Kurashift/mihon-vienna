package eu.kanade.tachiyomi.ui.reader.viewer.navigation

import android.graphics.RectF
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation

/**
 * Webtoon-specific tap zones. Since a vertical strip has no meaningful left/right
 * columns, the two side columns of the default layout are merged into the menu zone,
 * leaving only the top and bottom rows for scrolling.
 *
 * Visualization of default state without any inversion
 * +---+---+---+
 * | P | P | P |   P: Previous (scroll up)
 * +---+---+---+
 * | M | M | M |   M: Menu (open the reader bars)
 * +---+---+---+
 * | N | N | N |   N: Next (scroll down)
 * +---+---+---+
 *
 * In landscape the whole middle row stays the menu zone as well, which keeps the menu
 * reachable with the thumb of a hand holding the device on the left.
 */
class WebtoonNavigation : ViewerNavigation() {

    override var regionList: List<Region> = listOf(
        Region(
            rectF = RectF(0f, 0f, 1f, TOP_THIRD),
            type = NavigationRegion.PREV,
        ),
        Region(
            rectF = RectF(0f, TOP_THIRD, 1f, BOTTOM_THIRD),
            type = NavigationRegion.MENU,
        ),
        Region(
            rectF = RectF(0f, BOTTOM_THIRD, 1f, 1f),
            type = NavigationRegion.NEXT,
        ),
    )

    private companion object {
        const val TOP_THIRD = 1f / 3f
        const val BOTTOM_THIRD = 2f / 3f
    }
}
