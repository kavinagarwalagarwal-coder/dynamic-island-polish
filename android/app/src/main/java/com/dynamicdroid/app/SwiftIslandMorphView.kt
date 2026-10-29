package com.dynamicdroid.app

import android.content.Context
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
import com.dynamicdroid.islandmorphui.AppIslandMorphDemo
import skip.ui.ComposeContext

/** A service window supplies its own lifecycle for the Skip-translated SwiftUI composition. */
class SwiftIslandMorphView(context: Context, activity: LiveActivity) : FrameLayout(context),
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
                AppIslandMorphDemo(
                    title = activity.title,
                    subtitle = activity.subtitle,
                    appLabel = activity.appLabel,
                    isOverlay = true,
                    onClose = {},
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
