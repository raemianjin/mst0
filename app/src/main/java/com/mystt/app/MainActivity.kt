package com.mystt.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mystt.app.ui.MainViewModel
import com.mystt.app.ui.screens.HomeScreen
import com.mystt.app.ui.screens.LogScreen
import com.mystt.app.ui.screens.SettingsScreen
import com.mystt.app.ui.theme.MySttTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MySttTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val nav = rememberNavController()
                    NavHost(navController = nav, startDestination = "home") {
                        composable("home") {
                            HomeScreen(
                                vm = vm,
                                onOpenSettings = { nav.navigate("settings") },
                                onOpenLog = { nav.navigate("log") }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(vm = vm, onBack = { nav.popBackStack() })
                        }
                        composable("log") {
                            LogScreen(onBack = { nav.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}
