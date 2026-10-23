package app.discoredact.data

import android.content.Context
import java.io.File

/**
 * The sign-in screen's page data on disk, deleted without starting a WebView (so nothing is created in order to
 * delete it). Graph calls it from Wipe everything and once per process before the first sign-in page. It deletes,
 * under the app's private storage only:
 *  - every entry of the data directory whose name starts with `app_webview` (`app_webview/` and any suffixed
 *    sibling: cookies, local storage, IndexedDB, service workers, the HTTP-auth and form databases),
 *  - every entry of `cache/` whose name starts with `WebView` (`cache/WebView/`: the HTTP cache),
 *  - every entry of `cache/` whose name starts with `org.chromium.` (older WebView caches), if present.
 * Nothing else is touched. True when none of them is left.
 */
object SignInStorage {
    fun wipe(context: Context): Boolean {
        val app = context.applicationContext
        val targets = entries(app.dataDir) { it.startsWith("app_webview") } +
            entries(app.cacheDir) { it.startsWith("WebView") || it.startsWith("org.chromium.") }
        for (f in targets) f.deleteRecursively()
        return targets.none { it.exists() }
    }

    private fun entries(dir: File?, name: (String) -> Boolean): List<File> =
        dir?.listFiles()?.filter { name(it.name) }.orEmpty()
}
