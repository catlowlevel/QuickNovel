package com.lagradost.quicknovel.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File

class AiSummaryViewModel(application: Application) : AndroidViewModel(application) {
    val controller = AiSummaryController(viewModelScope, AiSettingsStore(application), AiProviderClient(),
        FileAiSummaryCache(File(application.noBackupFilesDir, "ai_summaries")))
}
