package com.lagradost.quicknovel.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lagradost.quicknovel.R

@Composable
internal fun BrowserRecovery(onRetry: () -> Unit, onOpenBrowser: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.error_loading))
        Text(stringResource(R.string.browser_recovery_hint))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onOpenBrowser) { Text(stringResource(R.string.browser_verify)) }
            Button(onClick = onRetry) { Text(stringResource(R.string.browser_retry)) }
        }
    }
}
