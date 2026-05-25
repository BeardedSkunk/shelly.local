package com.pearlnode

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.pearlnode.ui.AppNavHost
import com.pearlnode.ui.theme.AppTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = (application as PearlnodeApp).repository
        val initialDeviceId = intent.getStringExtra("deviceId")
        setContent {
            AppTheme {
                AppNavHost(repo, initialDeviceId = initialDeviceId)
            }
        }
    }
}
