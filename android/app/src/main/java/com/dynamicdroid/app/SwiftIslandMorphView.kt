package com.dynamicdroid.app

import android.content.Context
import android.graphics.RectF
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.dynamicdroid.islandmorphui.IslandReturnEntrance
import skip.ui.ComposeContext

/** A service window supplies its own lifecycle for the Skip-translated SwiftUI composition. */
class SwiftIslandMorphView(context: Context, bounds: RectF) : FrameLayout(context),
    LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry get() = savedStateController.savedStateRegistry

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        setViewTreeLifecycleOwner(this)
        setViewTreeSavedStateRegistryOwner(this)
        addView(ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                IslandReturnEntrance(
                    width = (bounds.width() / resources.displayMetrics.density).toDouble(),
                    height = (bounds.height() / resources.displayMetrics.density).toDouble(),
                    centerX = (bounds.centerX() / resources.displayMetrics.density).toDouble(),
                    centerY = (bounds.centerY() / resources.displayMetrics.density).toDouble(),
                ).Compose(context = ComposeContext())
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onDetachedFromWindow() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDetachedFromWindow()
    }
}
