package com.dinz.photoviewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.dinz.photoviewer.ui.PhotoViewerApp
import com.dinz.photoviewer.ui.timeline.TimelineViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: TimelineViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PhotoViewerApp(viewModel)
        }
    }
}
