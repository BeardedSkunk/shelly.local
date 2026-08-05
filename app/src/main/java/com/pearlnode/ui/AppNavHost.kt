package com.pearlnode.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pearlnode.data.DeviceRepository
import com.pearlnode.ui.screens.AddEditDeviceScreen
import com.pearlnode.ui.screens.DeviceControlScreen
import com.pearlnode.ui.screens.DeviceListScreen
import com.pearlnode.ui.screens.PowerScreen

@Composable
fun AppNavHost(repo: DeviceRepository, initialDeviceId: String? = null) {
    val nav = rememberNavController()
    val start = if (initialDeviceId != null) "control/$initialDeviceId" else "devices"

    NavHost(navController = nav, startDestination = start) {
        composable("devices") {
            DeviceListScreen(
                repo = repo,
                onAdd = { nav.navigate("add_device") },
                onDevice = { id -> nav.navigate("control/$id") },
                onEdit = { id -> nav.navigate("edit_device/$id") },
            )
        }
        composable("add_device") {
            AddEditDeviceScreen(repo = repo, deviceId = null, onDone = { nav.popBackStack() })
        }
        composable(
            "edit_device/{deviceId}",
            arguments = listOf(navArgument("deviceId") { type = NavType.StringType })
        ) { back ->
            AddEditDeviceScreen(
                repo = repo,
                deviceId = back.arguments?.getString("deviceId"),
                onDone = { nav.popBackStack() },
            )
        }
        composable(
            "control/{deviceId}",
            arguments = listOf(navArgument("deviceId") { type = NavType.StringType })
        ) { back ->
            val deviceId = back.arguments?.getString("deviceId") ?: return@composable
            DeviceControlScreen(
                repo = repo,
                deviceId = deviceId,
                onBack = { nav.popBackStack() },
                onPower = { nav.navigate("power/$deviceId") },
            )
        }
        composable(
            "power/{deviceId}",
            arguments = listOf(navArgument("deviceId") { type = NavType.StringType })
        ) { back ->
            val deviceId = back.arguments?.getString("deviceId") ?: return@composable
            PowerScreen(repo = repo, deviceId = deviceId, onBack = { nav.popBackStack() })
        }
    }
}
