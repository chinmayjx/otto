package io.github.otto.ui

import android.content.Context
import android.view.View
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.AndroidUiDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Material 3 with the wallpaper's colors (Android 12+, as the setup screen), light or dark as the phone is. The
 * shapes are the setup screen's: rounded, cards at 16dp.
 */
@Composable
fun OttoTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(
        colorScheme = scheme,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(6.dp),
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}

/** What the native sections ask of the second screen they're in (Screen implements it). */
interface ScreenOps {
    /** The viewer, over the whole second screen. hint: what the list already knows (the kind: HTML goes to the web viewer at once). */
    fun openViewer(target: Target, hint: ArtifactItem? = null)

    /** The viewer goes. */
    fun closeViewer()

    /** The viewer at this target, in the web viewer instead (HTML artifacts, and what has no native view). */
    fun webViewer(target: Target)

    /** A page in the second screen's browser. */
    fun openInBrowser(url: String)

    /** A file from the hub or the artifact hub into Downloads. */
    fun download(url: String, name: String)

    /** The share sheet with a link. */
    fun share(text: String, title: String)

    /** The screen out of the way (something opens under it: a share sheet, another app). */
    fun hide()

    /** Files: which of the top bar's buttons apply. */
    fun filesBar(up: Boolean, home: Boolean, hidden: Boolean) {}

    /** The Artifacts section shows again (after a delete, say). */
    fun artifactsChanged() {}

    /** The Devbox section. */
    fun showDevbox() {}
}

val LocalOps = staticCompositionLocalOf<ScreenOps> { error("no ScreenOps") }

/**
 * Compose in the second screen's views. The screen isn't an activity's (it moves between ScreenActivity windows
 * and lives in OverlayService), so it carries its own lifecycle, resumed while the screen exists, and its own
 * Recomposer: a window's would end with that window, and the views outlive it.
 */
class ComposeHost : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    private val scope = CoroutineScope(SupervisorJob() + AndroidUiDispatcher.Main)
    private val recomposer = Recomposer(scope.coroutineContext)

    init {
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        saved.performAttach()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    /** On the screen's root view, so every ComposeView under it finds the owners. */
    fun install(root: View) {
        root.setViewTreeLifecycleOwner(this)
        root.setViewTreeSavedStateRegistryOwner(this)
        root.setViewTreeViewModelStoreOwner(this)
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        recomposer.cancel()
        scope.cancel()
    }

    fun view(ctx: Context, ops: ScreenOps, content: @Composable () -> Unit): ComposeView = ComposeView(ctx).apply {
        setViewTreeLifecycleOwner(this@ComposeHost)
        setViewTreeSavedStateRegistryOwner(this@ComposeHost)
        setViewTreeViewModelStoreOwner(this@ComposeHost)
        setParentCompositionContext(recomposer)
        // Kept while the screen lives: its views are detached and attached again as ScreenActivity comes and goes.
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@ComposeHost))
        setContent {
            CompositionLocalProvider(LocalOps provides ops) {
                OttoTheme(content)
            }
        }
    }
}
