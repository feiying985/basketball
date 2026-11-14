package com.feiying.basketball.data

import android.content.Context
import androidx.room.Room
import com.feiying.basketball.data.local.AppDatabase
import com.feiying.basketball.data.ondevice.OnDeviceShotAnalyzer
import com.feiying.basketball.domain.SelectedVideo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

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
    ).addMigrations(
        AppDatabase.MIGRATION_1_2,
        AppDatabase.MIGRATION_2_3,
        AppDatabase.MIGRATION_3_4,
    ).fallbackToDestructiveMigration().build()

    val settingsStore = SettingsStore(appContext)
    val newAnalysisSession = NewAnalysisSession()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tokenProvider = TokenProvider()

    lateinit var authRepository: AuthRepository
        private set

    private val authInterceptor = AuthInterceptor(tokenProvider) {
        authRepository.onUnauthorized()
    }
    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .build()

    private val analysisApi = AnalysisApi(
        json = json,
        baseUrlProvider = { settingsStore.baseUrl.first() },
        client = httpClient,
    )

    val repository = AnalysisRepository(
        dao = database.analysisRecordDao(),
        api = analysisApi,
        settingsStore = settingsStore,
        onDeviceAnalyzer = OnDeviceShotAnalyzer(),
        json = json,
        scope = scope,
    )

    fun authToken(): String? = tokenProvider.token

    init {
        val authApi = AuthApi(
            json = json,
            baseUrlProvider = { settingsStore.baseUrl.first() },
            client = httpClient,
        )
        authRepository = AuthRepository(
            api = authApi,
            store = KeystoreSessionStore(appContext),
            tokenProvider = tokenProvider,
            scope = scope,
            onSessionChanged = { user -> repository.setOwner(user?.id) },
        )
    }
}
