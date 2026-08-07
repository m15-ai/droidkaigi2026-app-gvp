package com.m15.gvp

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.m15.gvp.ui.SettingsScreen
import com.m15.gvp.ui.SetupScreen
import com.m15.gvp.ui.VoiceAgentScreen
import com.m15.gvp.ui.theme.GvpTheme

class MainActivity : ComponentActivity() {

    private val vm by viewModels<VoiceAgentViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val themeMode by vm.themeMode.collectAsStateWithLifecycle()

            GvpTheme(themeMode = themeMode) {
                val nav = rememberNavController()
                val backStack by nav.currentBackStackEntryAsState()
                val currentRoute = backStack?.destination?.route

                val uiState by vm.ui.collectAsStateWithLifecycle()
                val showViz by vm.showVisualizer.collectAsStateWithLifecycle()
                val ttsLevel by vm.ttsLevel.collectAsStateWithLifecycle()
                val latencyMs by vm.latencyMs.collectAsStateWithLifecycle()
                val systemMessage by vm.systemMessage.collectAsStateWithLifecycle()
                val llmStatus by vm.llmStatus.collectAsStateWithLifecycle()
                val llmStatusDetail by vm.llmStatusDetail.collectAsStateWithLifecycle()
                val activeLlmModelName by vm.activeLlmModelName.collectAsStateWithLifecycle()
                val sttStatus by vm.sttStatus.collectAsStateWithLifecycle()
                val sttStatusDetail by vm.sttStatusDetail.collectAsStateWithLifecycle()

                // Permission flow: request mic (+ notifications on 33+); start session once granted.
                val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { result ->
                    if (result[Manifest.permission.RECORD_AUDIO] == true) {
                        vm.startSession()
                    }
                }

                fun requestAndStart() {
                    val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) {
                        perms.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    permLauncher.launch(perms.toTypedArray())
                }

                // Drive navigation from session state.
                LaunchedEffect(uiState.sessionActive) {
                    if (uiState.sessionActive && currentRoute != "voice") {
                        nav.navigate("voice")
                    } else if (!uiState.sessionActive && currentRoute == "voice") {
                        nav.popBackStack("setup", inclusive = false)
                    }
                }

                NavHost(navController = nav, startDestination = "setup") {
                    composable("setup") {
                        SetupScreen(
                            systemMessagePreview = systemMessage,
                            llmStatus = llmStatus,
                            llmStatusDetail = llmStatusDetail,
                            llmModelName = activeLlmModelName,
                            onLlmStatusTap = { vm.onLlmStatusTap() },
                            sttStatus = sttStatus,
                            sttStatusDetail = sttStatusDetail,
                            onSttStatusTap = { vm.onSttStatusTap() },
                            onEditSystemMessage = { nav.navigate("prompt") },
                            onStartSession = { requestAndStart() },
                            onOpenSettings = { nav.navigate("settings") }
                        )
                    }
                    composable("prompt") {
                        CustomPromptScreen(
                            initialPrompt = systemMessage,
                            onSave = { newMessage ->
                                vm.setSystemMessage(newMessage)
                                nav.popBackStack()
                            },
                            onCancel = { nav.popBackStack() }
                        )
                    }
                    composable("voice") {
                        VoiceAgentScreen(
                            ui = uiState,
                            isSpeakerOn = uiState.speakerOn,
                            onSpeakerToggle = { vm.toggleSpeaker() },
                            onDismissSession = { vm.stopSession() },
                            onToggleVisualizer = vm::toggleVisualizer,
                            showVisualizer = showViz,
                            ttsLevel = ttsLevel,
                            latencyMs = latencyMs,
                            onOpenSettings = { nav.navigate("settings") }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            vm = vm,
                            onBack = { nav.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        vm.stopSession()
    }
}
