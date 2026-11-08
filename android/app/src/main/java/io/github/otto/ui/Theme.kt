package io.github.otto.ui

import android.content.Context
import android.view.View
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
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

/** Dark: AMOLED black, surfaces one step up, brick red accent (as res/values-night/themes.xml). */
private val Dark = darkColorScheme(
    primary = Color(0xFFC8553D), onPrimary = Color(0xFF0B0C0E),
    primaryContainer = Color(0xFF3A1A13), onPrimaryContainer = Color(0xFFF6D3C9),
    secondary = Color(0xFFAEB4BC), onSecondary = Color(0xFF1A1D21),
    secondaryContainer = Color(0xFF24272B), onSecondaryContainer = Color(0xFFE3E6EA),
    tertiary = Color(0xFFAEB4BC), onTertiary = Color(0xFF1A1D21),
    background = Color.Black, onBackground = Color(0xFFEDEEF0),
    surface = Color.Black, onSurface = Color(0xFFEDEEF0),
    surfaceVariant = Color(0xFF17171A), onSurfaceVariant = Color(0xFF8E949C),
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF0B0B0C), surfaceContainer = Color(0xFF0E0E10),
    surfaceContainerHigh = Color(0xFF17171A), surfaceContainerHighest = Color(0xFF1E1E22),
    surfaceBright = Color(0xFF1E1E22), surfaceDim = Color.Black, surfaceTint = Color.Transparent,
    outline = Color(0xFF3A3A40), outlineVariant = Color(0xFF26262A),
    error = Color(0xFFFF6B81), onError = Color(0xFF2B0010),
    errorContainer = Color(0xFF3B0A18), onErrorContainer = Color(0xFFFFD9DF),
)

/** Light: white, with a darker brick accent (as res/values/themes.xml). */
private val Light = lightColorScheme(
    primary = Color(0xFFA8432D), onPrimary = Color.White,
    primaryContainer = Color(0xFFF9DED6), onPrimaryContainer = Color(0xFF3A0F06),
    secondary = Color(0xFF5E6570), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E6EA), onSecondaryContainer = Color(0xFF1A1D21),
    tertiary = Color(0xFF5E6570), onTertiary = Color.White,
    background = Color.White, onBackground = Color(0xFF111214),
    surface = Color.White, onSurface = Color(0xFF111214),
    surfaceVariant = Color(0xFFECECEF), onSurfaceVariant = Color(0xFF5E6570),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7F7F8), surfaceContainer = Color(0xFFF2F2F4),
    surfaceContainerHigh = Color(0xFFECECEF), surfaceContainerHighest = Color(0xFFE6E6EA),
    surfaceBright = Color.White, surfaceDim = Color(0xFFE6E6EA), surfaceTint = Color.Transparent,
    outline = Color(0xFFC5C8CE), outlineVariant = Color(0xFFE2E4E8),
    error = Color(0xFFC2185B), onError = Color.White,
    errorContainer = Color(0xFFFFD9E2), onErrorContainer = Color(0xFF3E0019),
)

/** Material 3 in Otto's own colours, light or dark as the phone is. Shapes: rounded, cards at 16dp. */
@Composable
fun OttoTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) Dark else Light
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
