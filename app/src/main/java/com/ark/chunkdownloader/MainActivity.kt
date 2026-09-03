package com.ark.chunkdownloader

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.ark.chunkdownloader.ui.navigation.ArkNavGraph
import com.ark.chunkdownloader.ui.theme.ArkBg
import com.ark.chunkdownloader.ui.theme.ArkTheme
import com.ark.chunkdownloader.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        enableEdgeToEdge()
        setContent {
            ArkTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = ArkBg) {
                    val nav = rememberNavController()
                    val vm: MainViewModel = viewModel()
                    ArkNavGraph(nav, vm)
                }
            }
        }
    }
}
