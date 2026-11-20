package com.expenses.app.ui.screens.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.window.Dialog
import com.expenses.app.R
import com.expenses.app.ui.components.AppButton
import com.expenses.app.ui.components.AppInput
import com.expenses.app.ui.components.ButtonSize
import com.expenses.app.ui.components.ButtonStyle
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Radius
import com.expenses.app.ui.theme.Space

/**
 * Debug builds only (not in the design): "Dev sign-in" under Not now. It asks for a name and signs in against the
 * local server as the test user of that name, so two test users can take turns on one emulator.
 *
 * @param onSignIn called with the name typed.
 */
@Composable
fun DevSignInEntry(onSignIn: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    AppButton(
        label = stringResource(R.string.signin_dev),
        onClick = { open = true },
        modifier = Modifier.fillMaxWidth(),
        style = ButtonStyle.Ghost,
        size = ButtonSize.Medium,
    )
    if (!open) return
    val submit = {
        if (name.isNotBlank()) {
            open = false
            onSignIn(name.trim())
        }
    }
    Dialog(onDismissRequest = { open = false }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppTheme.colors.bgSurface, RoundedCornerShape(Radius.xl))
                .padding(Space.s16),
            verticalArrangement = Arrangement.spacedBy(Space.s12),
        ) {
            AppText(text = stringResource(R.string.signin_dev_title), style = AppTheme.type.headingM)
            AppText(text = stringResource(R.string.signin_dev_hint), style = AppTheme.type.bodyM, color = AppTheme.colors.textSecondary)
            AppInput(
                value = name,
                onValueChange = { name = it.take(40) },
                label = stringResource(R.string.signin_dev_name),
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s12)) {
                AppButton(
                    label = stringResource(R.string.signin_dev_cancel),
                    onClick = { open = false },
                    modifier = Modifier.weight(1f),
                    style = ButtonStyle.Secondary,
                    size = ButtonSize.Medium,
                )
                AppButton(
                    label = stringResource(R.string.signin_dev_go),
                    onClick = submit,
                    modifier = Modifier.weight(1f),
                    size = ButtonSize.Medium,
                    enabled = name.isNotBlank(),
                )
            }
        }
    }
}
