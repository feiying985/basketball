package com.feiying.basketball.data

import android.content.Context
import androidx.room.Room
import com.feiying.basketball.data.local.AppDatabase
import com.feiying.basketball.domain.SelectedVideo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/** Video selected for the current capture flow, before a local record is created. */
class NewAnalysisSession {
    private val _selected = MutableStateFlow<SelectedVideo?>(null)
    val selected: StateFlow<SelectedVideo?> = _selected.asStateFlow()

    fun select(video: SelectedVideo) {
        _selected.value = video
    }

    fun clear() {
        _selected.value = null
    }
}

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    val database: AppDatabase = Room.databaseBuilder(
        appContext,
        AppDatabase::class.java,
        AppDatabase.NAME,
    ).fallbackToDestructiveMigration().build()

    val settingsStore = SettingsStore(appContext)
    val newAnalysisSession = NewAnalysisSession()

    private val api = AnalysisApi(
        json = json,
        baseUrlProvider = { settingsStore.baseUrl.first() },
    )
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val repository = AnalysisRepository(
        dao = database.analysisRecordDao(),
        api = api,
        settingsStore = settingsStore,
        json = json,
        scope = repositoryScope,
    )
}
