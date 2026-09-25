package com.phi3chat

import android.app.Application
import com.phi3chat.data.AppDatabase
import com.phi3chat.data.SettingsRepository
import com.phi3chat.engine.PhiEngine

/**
 * Minimal service locator. The app has exactly three long-lived collaborators,
 * so a DI framework would be more ceremony than value.
 */
class Phi3ChatApp : Application() {

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    val database: AppDatabase by lazy { AppDatabase.get(this) }

    val engine: PhiEngine by lazy { PhiEngine(this) }
}
