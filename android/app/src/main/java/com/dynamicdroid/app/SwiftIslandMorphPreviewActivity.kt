package com.dynamicdroid.app

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.dynamicdroid.islandmorphui.AppIslandMorphDemo
import skip.ui.ComposeContext

/** In-app preview of the same Skip-translated SwiftUI transition used by the overlay service. */
class SwiftIslandMorphPreviewActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Toast.makeText(this, "SwiftUI morph preview requires Android 9 or newer", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                AppIslandMorphDemo(
                    title = "Midnight City",
                    subtitle = "M83 · Hurry Up, We're Dreaming",
                    appLabel = "Spotify",
                    onClose = { finish() },
                ).Compose(context = ComposeContext())
            }
        })
    }
}
