package com.theoriacodex.data.repository

class InMemoryLibraryContractTest : LibraryRepositoryContract() {
    override fun createCodexRepository(): CodexRepository = InMemoryCodexRepository()

    override fun transactionsFor(repository: CodexRepository): CodexLikesTransactions =
        InMemoryCodexLikesTransactions(repository)

    override fun likesFor(transactions: CodexLikesTransactions): LikesRepository =
        (transactions as InMemoryCodexLikesTransactions).likes

    override fun createRecentsRepository(clock: () -> Long, watchedLimit: Int, searchLimit: Int): RecentsRepository =
        InMemoryRecentsRepository(watchedLimit, searchLimit, clock)
}
