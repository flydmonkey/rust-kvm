package com.rustkvm.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rustkvm.app.data.RecentServersStore
import com.rustkvm.app.trust.SharedPrefsCertTrustStore
import com.rustkvm.app.ui.ConnectScreen
import com.rustkvm.app.ui.ControlScreen
import com.rustkvm.app.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 控制页要全屏、要拿到 IME 高度给功能键栏让位，都依赖 edge-to-edge；
        // 其他页面自己用 safeDrawingPadding 避开系统栏
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    val trustStore = remember { SharedPrefsCertTrustStore(applicationContext) }
                    val recentServersStore = remember { RecentServersStore(applicationContext) }

                    NavHost(navController = navController, startDestination = "connect") {
                        composable("connect") {
                            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                                ConnectScreen(
                                    trustStore = trustStore,
                                    recentServersStore = recentServersStore,
                                    onConnected = { baseUrl ->
                                        navController.navigate("control/${Uri.encode(baseUrl)}") {
                                            launchSingleTop = true
                                        }
                                    },
                                )
                            }
                        }
                        composable(
                            route = "control/{baseUrl}",
                            arguments = listOf(
                                navArgument("baseUrl") { type = NavType.StringType },
                            ),
                        ) { entry ->
                            val baseUrl = Uri.decode(
                                requireNotNull(entry.arguments?.getString("baseUrl")),
                            )
                            ControlScreen(
                                baseUrl = baseUrl,
                                trustStore = trustStore,
                                onDisconnect = { navController.popBackStack() },
                                onOpenSettings = {
                                    navController.navigate("settings/${Uri.encode(baseUrl)}") {
                                        launchSingleTop = true
                                    }
                                },
                            )
                        }
                        composable(
                            route = "settings/{baseUrl}",
                            arguments = listOf(
                                navArgument("baseUrl") { type = NavType.StringType },
                            ),
                        ) { entry ->
                            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                                SettingsScreen(
                                    baseUrl = Uri.decode(
                                        requireNotNull(entry.arguments?.getString("baseUrl")),
                                    ),
                                    trustStore = trustStore,
                                    onBack = { navController.popBackStack() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
