package com.theoriacodex.app.settings.sites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.customsites.CustomSiteAvailability
import com.theoriacodex.app.customsites.CustomSiteSummary
import com.theoriacodex.app.settings.ExpandableSection
import com.theoriacodex.app.settings.SettingsGroup
import com.theoriacodex.app.settings.SettingsHeader
import com.theoriacodex.app.settings.SettingsPageColumn
import com.theoriacodex.app.settings.SettingsRow
import com.theoriacodex.domain.source.ProtocolFamily
import com.theoriacodex.domain.source.SiteProfile

/** Add flow: address → probe → keys if asked → confirm name and opt-in → save. */
@Composable
internal fun AddSitePage(form: SiteForm.Add, onAction: (CustomSitesAction) -> Unit) {
    val stage = form.stage
    val editable = !stage.busy
    SettingsPageColumn {
        SettingsHeader("Add site", onBack = { onAction(CustomSitesAction.CloseForm) }, parents = SITES_PATH)
        SettingsGroup(null) {
            FormColumn {
                val addressMessage = (stage as? AddStage.Address)?.message
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = form.address,
                    onValueChange = { onAction(CustomSitesAction.SetAddress(it)) },
                    label = { Text("Site address") },
                    placeholder = { Text("safebooru.org") },
                    supportingText = addressMessage?.let { message -> { AnnouncedText(message) } },
                    isError = addressMessage != null,
                    singleLine = true,
                    enabled = editable,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onAction(CustomSitesAction.Probe) }),
                )
                if (form.showCredentials) {
                    CredentialFields(form.fields, stage.knownFamily, editable, onAction)
                } else if (editable) {
                    TextButton(onClick = { onAction(CustomSitesAction.ShowCredentials) }) { Text("I have an API key") }
                }
                AdvancedHeaders(form.fields, editable, onAction)
                AddStageControls(form, onAction)
            }
        }
    }
}

@Composable
private fun AddStageControls(form: SiteForm.Add, onAction: (CustomSitesAction) -> Unit) {
    when (val stage = form.stage) {
        is AddStage.Address -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onAction(CustomSitesAction.Probe) }) { Text("Check site") }
            stage.blockedHost?.let { host ->
                TextButton(onClick = { onAction(CustomSitesAction.StartVerification(host, afterRefusal = true)) }) { Text("Verify in browser") }
            }
        }
        AddStage.Probing -> Progress("Checking site…")
        is AddStage.Credentials -> {
            AnnouncedText(
                if (stage.rejected) "Those keys didn't work. Check them and try again." else "This site needs an API key.",
                error = stage.rejected,
            )
            Button(onClick = { onAction(CustomSitesAction.Probe) }) { Text("Check again") }
        }
        is AddStage.Confirm -> ConfirmSection(form, stage.probe.family, stage.message, saving = false, onAction)
        is AddStage.Saving -> ConfirmSection(form, stage.probe.family, null, saving = true, onAction)
        is AddStage.Reconnect -> ConfirmSection(form, stage.probe.family, null, saving = true, onAction)
    }
}

@Composable
private fun ConfirmSection(
    form: SiteForm.Add,
    family: ProtocolFamily,
    message: String?,
    saving: Boolean,
    onAction: (CustomSitesAction) -> Unit,
) {
    AnnouncedText("Found a ${family.displayName} site. Name it and add it.")
    LabelField(form.fields.label, !saving, onAction)
    IncludeSwitch(form.fields.includeInUnifiedAndForYou, !saving, onAction)
    message?.let { AnnouncedText(it, error = true) }
    if (saving) {
        Progress("Adding site…")
    } else {
        Button(onClick = { onAction(CustomSitesAction.SaveNew) }) { Text("Add site") }
    }
}

/** Edit flow: name, opt-in, keys (replace or remove) and headers. Never changes the address or family. */
@Composable
internal fun EditSitePage(
    form: SiteForm.Edit,
    site: CustomSiteSummary,
    retest: RetestStatus?,
    working: Boolean,
    onAction: (CustomSitesAction) -> Unit,
) {
    val editable = !form.saving
    SettingsPageColumn {
        SettingsHeader(site.label, onBack = { onAction(CustomSitesAction.CloseForm) }, parents = SITES_PATH)
        SettingsGroup(null) {
            FormColumn {
                Text(site.baseUrl, style = MaterialTheme.typography.bodyMedium)
                Text(
                    siteStatusSummary(site),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LabelField(form.fields.label, editable, onAction)
                IncludeSwitch(form.fields.includeInUnifiedAndForYou, editable, onAction)
            }
        }
        SettingsGroup("Browser check") {
            SettingsRow(
                title = "Verify in browser",
                summary = "Open this site to pass its browser check",
                enabled = editable,
                onClick = { onAction(CustomSitesAction.StartVerification(site.host, afterRefusal = false)) },
                trailing = if (working) { { WorkingMark() } } else null,
            )
            // Why the last check failed; a pass shows as the row's mark once the page closes itself.
            (retest as? RetestStatus.Done)?.takeUnless { it.ok }?.let {
                AnnouncedText(it.message, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), error = true)
            }
        }
        SettingsGroup(null) {
            FormColumn {
                // Opens by itself only when the site can't work without keys.
                ExpandableSection("API key", initiallyExpanded = site.availability == CustomSiteAvailability.NEEDS_CREDENTIALS) {
                    EditCredentials(form, site.family, editable, onAction)
                }
                AdvancedHeaders(form.fields, editable, onAction)
            }
        }
        form.message?.let { AnnouncedText(it, Modifier.padding(horizontal = 4.dp), error = true) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onAction(CustomSitesAction.SaveEdit) }, enabled = editable) { Text("Save") }
            TextButton(onClick = { onAction(CustomSitesAction.RequestRemove(site.sourceId)) }, enabled = editable) {
                Text("Remove site", color = MaterialTheme.colorScheme.error)
            }
            if (form.saving) Progress("Saving…")
        }
    }
}

@Composable
private fun EditCredentials(
    form: SiteForm.Edit,
    family: ProtocolFamily?,
    editable: Boolean,
    onAction: (CustomSitesAction) -> Unit,
) {
    when {
        form.clearCredentials -> {
            Text("Saved keys will be removed when you save.")
            TextButton(onClick = { onAction(CustomSitesAction.SetClearCredentials(false)) }, enabled = editable) {
                Text("Keep saved keys")
            }
        }
        else -> {
            Text(if (form.hasCredentials) "Keys saved. Enter new ones to replace them." else "No keys saved.")
            CredentialFields(form.fields, family, editable, onAction)
            if (form.hasCredentials) {
                TextButton(onClick = { onAction(CustomSitesAction.SetClearCredentials(true)) }, enabled = editable) {
                    Text("Remove saved keys")
                }
            }
        }
    }
}

/** User and masked API key. The user field is named the way [family] names it. */
@Composable
private fun CredentialFields(
    fields: SiteFields,
    family: ProtocolFamily?,
    enabled: Boolean,
    onAction: (CustomSitesAction) -> Unit,
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = fields.user,
        onValueChange = { onAction(CustomSitesAction.SetUser(it)) },
        label = { Text(userFieldLabel(family)) },
        supportingText = { Text(userFieldHint(family, fields.user)?.text ?: "Or paste the key snippet from your account page.") },
        isError = userFieldHint(family, fields.user)?.error == true,
        singleLine = true,
        enabled = enabled,
    )
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = fields.apiKey,
        onValueChange = { onAction(CustomSitesAction.SetApiKey(it)) },
        label = { Text("API key") },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}

@Composable
private fun LabelField(label: String, enabled: Boolean, onAction: (CustomSitesAction) -> Unit) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = label,
        onValueChange = { onAction(CustomSitesAction.SetLabel(it.take(SiteProfile.MAX_LABEL_LENGTH))) },
        label = { Text("Name") },
        supportingText = { Text("${label.length}/${SiteProfile.MAX_LABEL_LENGTH}") },
        singleLine = true,
        enabled = enabled,
    )
}

/** The single opt-in shared by Unified search and For You (plan D4); new sites start off. */
@Composable
private fun IncludeSwitch(checked: Boolean, enabled: Boolean, onAction: (CustomSitesAction) -> Unit) {
    SettingsRow(
        "Include in Unified and For You",
        "Off keeps this site to its own searches",
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = { onAction(CustomSitesAction.SetIncludeInUnifiedAndForYou(it)) },
                enabled = enabled,
            )
        },
    )
}

/** Collapsed User-Agent and Referer overrides; blank fields use the defaults. */
@Composable
private fun AdvancedHeaders(fields: SiteFields, enabled: Boolean, onAction: (CustomSitesAction) -> Unit) {
    // Read once: a value typed while open must not fold the section away under the person typing.
    val startExpanded = rememberSaveable { fields.userAgent.isNotEmpty() || fields.referer.isNotEmpty() }
    ExpandableSection("Advanced", initiallyExpanded = startExpanded) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = fields.userAgent,
            onValueChange = { onAction(CustomSitesAction.SetUserAgent(it)) },
            label = { Text("User-Agent") },
            supportingText = { Text("Leave blank to use the default.") },
            singleLine = true,
            enabled = enabled,
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = fields.referer,
            onValueChange = { onAction(CustomSitesAction.SetReferer(it)) },
            label = { Text("Referer") },
            supportingText = { Text("Leave blank to use the site's address.") },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
    }
}

@Composable
private fun FormColumn(content: @Composable () -> Unit) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** A spinner with its words, so progress is announced and never conveyed by motion alone. */
@Composable
internal fun Progress(text: String) {
    Row(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Status text that TalkBack reads when it appears or changes. */
@Composable
internal fun AnnouncedText(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Text(
        text,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

private val AddStage.knownFamily: ProtocolFamily?
    get() = when (this) {
        is AddStage.Credentials -> family
        is AddStage.Confirm -> probe.family
        is AddStage.Saving -> probe.family
        is AddStage.Reconnect -> probe.family
        is AddStage.Address, AddStage.Probing -> null
    }
