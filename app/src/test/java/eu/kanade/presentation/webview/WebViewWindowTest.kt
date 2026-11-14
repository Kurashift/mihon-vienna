package eu.kanade.presentation.webview

import android.view.ViewGroup
import android.webkit.WebView
import com.kevinnzou.web.WebContent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WebViewWindowTest {

    @Test
    fun `destroy detaches the WebView before destroying it and clears ownership first`() {
        val window = WebViewWindow(WebContent.NavigatorOnly, mockk())
        val parent = mockk<ViewGroup>(relaxed = true)
        val view = mockk<WebView>(relaxed = true)
        every { view.parent } returns parent
        every { parent.removeView(view) } answers { assertNull(window.webView) }
        window.webView = view

        window.destroyWebView()

        assertNull(window.webView)
        verifyOrder {
            parent.removeView(view)
            view.destroy()
        }
    }

    @Test
    fun `renderer cleanup followed by composable disposal destroys only once`() {
        val window = WebViewWindow(WebContent.NavigatorOnly, mockk())
        val view = mockk<WebView>(relaxed = true)
        every { view.parent } returns null
        window.webView = view

        window.destroyWebView()
        window.destroyWebView()

        assertNull(window.webView)
        verify(exactly = 1) { view.destroy() }
        verify(exactly = 0) {
            view.stopLoading()
            view.settings
            view.url
        }
    }

    @Test
    fun `destroying an unopened popup is safe`() {
        val window = WebViewWindow(WebContent.NavigatorOnly, mockk())

        window.destroyWebView()

        assertNull(window.webView)
    }

    @Test
    fun `closing all windows releases both active and retained popup instances`() {
        val windows = List(3) { WebViewWindow(WebContent.NavigatorOnly, mockk()) }
        val views = List(3) { mockk<WebView>(relaxed = true) }
        windows.zip(views).forEach { (window, view) ->
            every { view.parent } returns null
            window.webView = view
        }

        windows.forEach { it.destroyWebView() }
        windows.forEach { it.destroyWebView() }

        windows.forEach { assertNull(it.webView) }
        views.forEach { view -> verify(exactly = 1) { view.destroy() } }
    }
}
