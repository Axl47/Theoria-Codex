package com.theoriacodex.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shared bounded list surface for plain and faceted search suggestions. */
@Composable
fun <Item> AutocompleteListShell(
    items: List<Item>,
    rowContent: @Composable (Item) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(
                items = items,
            ) { index, item ->
                rowContent(item)
                if (index != items.lastIndex) {
                    HorizontalDivider()
                }
            }
        }
    }
}
