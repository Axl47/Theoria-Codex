package com.theoriacodex.app.tags

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theoriacodex.app.search.searchTermTypeLabel
import com.theoriacodex.app.ui.components.TagGroupCard
import com.theoriacodex.app.ui.components.TagGroupOrLabel
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup

/** A TalkBack action beside the chip's tap, reaching a state directly instead of through the cycle. */
internal data class TagChipAction(val label: String, val run: () -> Unit)

/**
 * One tag in a tag list. Its full name wraps with its neighbors, and its count sits in a fixed slot
 * so counts that arrive late never move the chips around it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TagPickerChip(
    label: String,
    count: String?,
    tapLabel: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    state: TagChipState = TagChipState.NONE,
    enabled: Boolean = true,
    alternateActions: List<TagChipAction> = emptyList(),
    onLongPress: (() -> Unit)? = null,
    longPressLabel: String? = null,
    leadingIcon: ImageVector? = null,
    reserveCountSlot: Boolean = true,
    /** Extra state TalkBack reads after the search state, such as "saved". */
    stateSuffix: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    val tint = tagChipTint(state)
    val neutral = state == TagChipState.NONE
    Surface(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .alpha(if (enabled) 1f else DIMMED_ALPHA)
            .clip(shape)
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = tapLabel,
                onLongClick = onLongPress,
                onLongClickLabel = longPressLabel,
                onClick = onTap,
            )
            .semantics {
                selected = !neutral
                stateDescription = listOfNotNull(state.description, stateSuffix).joinToString(", ")
                customActions = alternateActions.map { action ->
                    CustomAccessibilityAction(action.label) { action.run(); true }
                }
            },
        shape = shape,
        color = if (neutral) colors.surface.copy(alpha = 0f) else tint.copy(alpha = 0.14f),
        contentColor = tint,
        border = BorderStroke(1.dp, if (neutral) colors.outlineVariant else tint),
    ) {
        TagChipContent(label, count, state, if (neutral) colors.onSurfaceVariant else tint, leadingIcon, reserveCountSlot)
    }
}

@Composable
private fun tagChipTint(state: TagChipState) = when (state) {
    TagChipState.NONE -> MaterialTheme.colorScheme.onSurface
    TagChipState.REQUIRED -> MaterialTheme.colorScheme.primary
    TagChipState.EXCLUDED -> MaterialTheme.colorScheme.error
}

@Composable
private fun TagChipContent(
    label: String,
    count: String?,
    state: TagChipState,
    countColor: Color,
    leadingIcon: ImageVector?,
    reserveCountSlot: Boolean,
) {
    Row(
        modifier = Modifier.height(CHIP_HEIGHT).padding(start = 10.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingIcon?.let { Icon(it, null, Modifier.size(16.dp), MaterialTheme.colorScheme.primary) }
        state.mark?.let {
            Text(it, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics {})
        }
        Text(
            label,
            modifier = Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (reserveCountSlot) {
            Box(Modifier.width(with(LocalDensity.current) { COUNT_SLOT_WIDTH.sp.toDp() }), Alignment.CenterEnd) {
                count?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = countColor, maxLines = 1) }
            }
        }
    }
}

/** Keeps long tag lists lazy: each chunk is its own list item, so offscreen chips never compose. */
internal fun <T> List<T>.tagChipChunks(): List<List<T>> = chunked(TAG_CHIPS_PER_LIST_ITEM)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagChipRow(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** A warning shown above the query, for an edit that waits on the user's answer. */
internal class TrayConfirmation(
    val message: String,
    val confirmLabel: String,
    val dismissLabel: String,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * The query being built, pinned under a tag list: every group must match, a group with several tags
 * is a capsule of alternatives, and excluded tags follow. "+ or" makes a group the target of the
 * next tap. [onSearch] is null where the current screen has no way to reach Search.
 */
@Composable
internal fun QueryTray(
    title: String,
    emptyText: String,
    query: Query?,
    targetGroup: Int?,
    onTargetGroup: (Int?) -> Unit,
    onSearch: (() -> Unit)?,
    modifier: Modifier = Modifier,
    confirmation: TrayConfirmation? = null,
) {
    val groups = query?.effectiveIncludeTermGroups.orEmpty()
    val excludes = query?.excludeTerms.orEmpty()
    val isEmpty = groups.isEmpty() && excludes.isEmpty()
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider()
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                onSearch?.let { search -> Button(onClick = search, enabled = !isEmpty) { Text("Search") } }
            }
            confirmation?.let { TrayConfirmationRow(it) }
            if (isEmpty) {
                Text(
                    emptyText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TrayExpression(groups, excludes, targetGroup, onTargetGroup)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrayExpression(
    groups: List<SearchTermGroup>,
    excludes: List<SearchTerm>,
    targetGroup: Int?,
    onTargetGroup: (Int?) -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = TRAY_QUERY_MAX_HEIGHT)
            .verticalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        groups.forEachIndexed { index, group ->
            TagGroupCard(active = index == targetGroup, contentPadding = PaddingValues(horizontal = 8.dp)) {
                group.terms.forEachIndexed { termIndex, term ->
                    if (termIndex > 0) TagGroupOrLabel()
                    TrayTerm(term)
                }
                if (index == targetGroup) {
                    TextButton(onClick = { onTargetGroup(null) }) { Text("Done") }
                } else {
                    TextButton(onClick = { onTargetGroup(index) }) { Text("+ or") }
                }
            }
        }
        excludes.forEach { term -> TrayTerm(term, excluded = true) }
    }
}

@Composable
private fun TrayConfirmationRow(confirmation: TrayConfirmation) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(
            confirmation.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = confirmation.onConfirm) { Text(confirmation.confirmLabel) }
            TextButton(onClick = confirmation.onDismiss) { Text(confirmation.dismissLabel) }
        }
    }
}

@Composable
private fun TrayTerm(term: SearchTerm, excluded: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val text = buildAnnotatedString {
        if (excluded) append("− ")
        searchTermTypeLabel(term)?.let { type ->
            withStyle(SpanStyle(color = colors.onSurfaceVariant)) { append(type) }
            append(" ")
        }
        append(term.value)
    }
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (excluded) colors.error else colors.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private val CHIP_HEIGHT = 36.dp
private val TRAY_QUERY_MAX_HEIGHT = 112.dp
private const val COUNT_SLOT_WIDTH = 26
private const val DIMMED_ALPHA = 0.38f
private const val TAG_CHIPS_PER_LIST_ITEM = 24
