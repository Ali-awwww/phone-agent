package com.example.di

import android.content.Context
import com.example.data.ai.GeminiRepository
import com.example.data.local.ActionLogRepository
import com.example.data.local.PhoneAgentDatabase
import com.example.executor.CommandExecutor
import com.example.files.PhoneFileManager
import com.example.organizer.AppOrganizer
import com.example.policy.AgentPolicy
import com.example.policy.SensitiveActionGate
import com.example.security.FirebaseAppCheckClient
import com.example.verifier.ScreenVerifier
import com.example.voice.VoiceAssistantManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database by lazy { PhoneAgentDatabase.getInstance(appContext) }
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val agentPolicy by lazy {
        AgentPolicy()
    }

    val sensitiveActionGate by lazy {
        SensitiveActionGate(agentPolicy = agentPolicy)
    }

    val actionLogRepository by lazy {
        ActionLogRepository(database.actionLogDao())
    }

    val geminiRepository by lazy {
        GeminiRepository(
            appCheckClient = FirebaseAppCheckClient(appContext)
        )
    }

    val screenVerifier by lazy {
        ScreenVerifier(
            context = appContext,
            geminiRepository = geminiRepository
        )
    }

    val commandExecutor by lazy {
        CommandExecutor(
            context = appContext,
            actionLogRepository = actionLogRepository,
            geminiRepository = geminiRepository,
            screenVerifier = screenVerifier,
            agentPolicy = agentPolicy,
            sensitiveActionGate = sensitiveActionGate
        )
    }

    val appOrganizer by lazy {
        AppOrganizer(
            context = appContext,
            appOrganizationDao = database.appOrganizationDao(),
            actionLogRepository = actionLogRepository,
            geminiRepository = geminiRepository
        )
    }

    val phoneFileManager by lazy {
        PhoneFileManager(
            context = appContext,
            actionLogRepository = actionLogRepository,
            agentPolicy = agentPolicy
        )
    }

    val voiceAssistantManager by lazy {
        VoiceAssistantManager(appContext)
    }

    init {
        initScope.launch {
            runCatching { appOrganizer.seedInitialOrganizationsOnce() }
        }
    }
}
