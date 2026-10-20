package com.lagradost.quicknovel.ai

import com.lagradost.quicknovel.R

internal fun AiFailure.messageRes(): Int =
    when (this) {
        AiFailure.MISSING_KEY -> R.string.ai_error_missing_key
        AiFailure.INVALID_KEY -> R.string.ai_error_invalid_key
        AiFailure.MISSING_MODEL -> R.string.ai_error_missing_model
        AiFailure.INVALID_MODEL -> R.string.ai_error_invalid_model
        AiFailure.INVALID_URL -> R.string.ai_error_invalid_url
        AiFailure.SOURCE_CHANGED -> R.string.ai_summary_source_changed
        AiFailure.EMPTY_CHAPTER -> R.string.ai_summary_unavailable
        AiFailure.REQUEST_TOO_LARGE -> R.string.ai_error_request_too_large
        AiFailure.REQUEST_REJECTED -> R.string.ai_error_request_rejected
        AiFailure.RESPONSE_BLOCKED -> R.string.ai_error_response_blocked
        AiFailure.EMPTY_RESPONSE -> R.string.ai_error_empty_response
        AiFailure.AUTHENTICATION -> R.string.ai_error_authentication
        AiFailure.FORBIDDEN -> R.string.ai_error_forbidden
        AiFailure.NOT_FOUND -> R.string.ai_error_not_found
        AiFailure.RATE_LIMIT -> R.string.ai_error_rate_limit
        AiFailure.SERVER -> R.string.ai_error_server
        AiFailure.NETWORK -> R.string.ai_error_network
        AiFailure.TIMEOUT -> R.string.ai_error_timeout
        AiFailure.INVALID_RESPONSE -> R.string.ai_error_response
        AiFailure.UNSUPPORTED_MODEL -> R.string.ai_error_unsupported
        AiFailure.LOAD_SETTINGS -> R.string.ai_error_load
        AiFailure.SAVE_SETTINGS -> R.string.ai_error_save
    }
