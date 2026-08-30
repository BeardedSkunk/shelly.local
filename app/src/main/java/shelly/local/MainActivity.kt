package shelly.local

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import shelly.local.ui.AppNavHost
import shelly.local.ui.theme.AppTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = (application as ShellyLocalApp).repository
        val initialDeviceId = intent.getStringExtra("deviceId")
        setContent {
            AppTheme {
                AppNavHost(repo, initialDeviceId = initialDeviceId)
            }
        }
    }
}
