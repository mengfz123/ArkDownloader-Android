package com.ark.chunkdownloader.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.ark.chunkdownloader.ui.screens.CreateTaskScreen
import com.ark.chunkdownloader.ui.screens.HomeScreen
import com.ark.chunkdownloader.ui.screens.SettingsScreen
import com.ark.chunkdownloader.ui.viewmodel.MainViewModel

object Routes {
    const val HOME = "home"
    const val CREATE = "create"
    const val SETTINGS = "settings"
}

@Composable
fun ArkNavGraph(nav: NavHostController, vm: MainViewModel) {
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                vm = vm,
                onCreate = { nav.navigate(Routes.CREATE) },
                onSettings = { nav.navigate(Routes.SETTINGS) }
            )
        }
        composable(Routes.CREATE) {
            CreateTaskScreen(vm = vm, onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(vm = vm, onBack = { nav.popBackStack() })
        }
    }
}
