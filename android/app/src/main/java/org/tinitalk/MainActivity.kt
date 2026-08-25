package org.tinitalk

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = RootUiModel().title
                gravity = Gravity.CENTER
                textSize = 28f
            }
        )
    }
}
