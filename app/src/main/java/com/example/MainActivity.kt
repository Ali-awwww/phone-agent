package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.example.di.AppContainer
import com.example.security.configureAppCheck
import com.example.ui.navigation.PhoneAgentApp
import com.example.ui.theme.PhoneAgentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // تهيئة App Check أولاً مع تمرير Intent الإطلاق قبل واجهة Compose أو ViewModels
        configureAppCheck(this, intent)

        enableEdgeToEdge()
        val appContainer = AppContainer(applicationContext)

        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                PhoneAgentTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        PhoneAgentApp(
                            appContainer = appContainer,
                            applicationContext = applicationContext
                        )
                    }
                }
            }
        }
    }
}
