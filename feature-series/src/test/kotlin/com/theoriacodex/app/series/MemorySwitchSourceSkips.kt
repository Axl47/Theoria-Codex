package com.theoriacodex.app.series

import com.theoriacodex.data.repository.SwitchSourceSkips
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class MemorySwitchSourceSkips(skipped: Set<SourceKey> = emptySet()) : SwitchSourceSkips {
    private val stored = MutableStateFlow(skipped)
    val skipped: Set<SourceKey> get() = stored.value

    override fun observeSwitchSourceSkipped(): Flow<Set<SourceKey>> = stored

    override suspend fun updateSwitchSourceSkipped(transform: (Set<SourceKey>) -> Set<SourceKey>) {
        stored.value = transform(stored.value)
    }
}
