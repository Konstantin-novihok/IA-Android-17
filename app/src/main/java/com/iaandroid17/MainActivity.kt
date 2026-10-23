package com.iaandroid17

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.iaandroid17.ui.AppViewModel
import com.iaandroid17.ui.IaApp

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { IaApp(vm) }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        vm.onTrimMemory(level)
    }
}
