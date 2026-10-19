package com.lagradost.quicknovel.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

class AiSettingsViewModel(application: Application) : AndroidViewModel(application) {
    val controller = AiSettingsController(viewModelScope, AiSettingsStore(application), AiProviderClient())
}
