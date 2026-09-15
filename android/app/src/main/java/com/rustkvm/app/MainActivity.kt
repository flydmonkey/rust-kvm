package com.rustkvm.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rustkvm.app.data.RecentServersStore
import com.rustkvm.app.trust.SharedPrefsCertTrustStore
import com.rustkvm.app.ui.ConnectScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    val trustStore = remember { SharedPrefsCertTrustStore(applicationContext) }
                    val recentServersStore = remember { RecentServersStore(applicationContext) }

                    NavHost(navController = navController, startDestination = "connect") {
                        composable("connect") {
                            ConnectScreen(
                                trustStore = trustStore,
                                recentServersStore = recentServersStore,
                                onConnected = { baseUrl ->
                                    navController.navigate("control/${Uri.encode(baseUrl)}")
                                },
                            )
                        }
                        composable(
                            route = "control/{baseUrl}",
                            arguments = listOf(
                                navArgument("baseUrl") { type = NavType.StringType },
                            ),
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("控制页占位")
                            }
                        }
                    }
                }
            }
        }
    }
}
