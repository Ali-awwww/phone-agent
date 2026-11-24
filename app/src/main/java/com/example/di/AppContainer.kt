package com.example.di

import android.content.Context
import com.example.data.ai.GeminiRepository
import com.example.data.local.ActionLogRepository
import com.example.data.local.PhoneAgentDatabase
import com.example.executor.CommandExecutor
import com.example.organizer.AppOrganizer
import com.example.policy.AgentPolicy
import com.example.verifier.ScreenVerifier
import com.example.voice.VoiceAssistantManager

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database by lazy { PhoneAgentDatabase.getInstance(appContext) }

    val agentPolicy by lazy {
        AgentPolicy()
    }

    val actionLogRepository by lazy {
        ActionLogRepository(database.actionLogDao())
    }

    val geminiRepository by lazy {
        GeminiRepository()
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
            agentPolicy = agentPolicy
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

    val voiceAssistantManager by lazy {
        VoiceAssistantManager(appContext)
    }
}
