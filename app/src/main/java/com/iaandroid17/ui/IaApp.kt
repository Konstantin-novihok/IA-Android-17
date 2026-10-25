package com.iaandroid17.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

@Composable
fun IaApp(vm: AppViewModel) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when (vm.settings.themeStyle) {
        "indigo" -> if (dark) darkColorScheme(primary = Color(0xFFBAC3FF), secondary = Color(0xFFC3C4DD), tertiary = Color(0xFFE2BBDD)) else lightColorScheme(primary = Color(0xFF4055A9), secondary = Color(0xFF585D78), tertiary = Color(0xFF76536F))
        "forest" -> if (dark) darkColorScheme(primary = Color(0xFF9ED5B4), secondary = Color(0xFFB5CCBB), tertiary = Color(0xFFD8C58A)) else lightColorScheme(primary = Color(0xFF246644), secondary = Color(0xFF4E6356), tertiary = Color(0xFF75602C))
        "sunset" -> if (dark) darkColorScheme(primary = Color(0xFFFFB4A8), secondary = Color(0xFFE7BDB5), tertiary = Color(0xFFD8C38D)) else lightColorScheme(primary = Color(0xFF984838), secondary = Color(0xFF775750), tertiary = Color(0xFF705F32))
        else -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    MaterialTheme(colorScheme = scheme) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("💬") }, label = { Text("Помощник") })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("📄") }, label = { Text("Документы") })
                    NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Text("⚙️") }, label = { Text("Настройки") })
                }
            }
        ) { inner ->
            Box(Modifier.padding(inner).fillMaxSize()) {
                when (tab) {
                    0 -> ChatScreen(vm, onOpenSettings = { tab = 2 })
                    1 -> DocsScreen(vm)
                    else -> SettingsScreen(vm)
                }
            }
        }
    }
}
