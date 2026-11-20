package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.MemoryStateCell

class InMemoryStatisticsRepository(
    initial: LifetimeStatistics = LifetimeStatistics(),
) : StatisticsRepository by CellStatisticsRepository(MemoryStateCell(StatisticsPolicies.normalize(initial)))
