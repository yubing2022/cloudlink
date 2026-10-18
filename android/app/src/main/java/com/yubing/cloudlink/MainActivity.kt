package com.yubing.cloudlink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.yubing.cloudlink.nav.AppNavGraph
import com.yubing.cloudlink.ui.theme.CloudLinkTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CloudLinkTheme {
                AppNavGraph()
            }
        }
    }
}
