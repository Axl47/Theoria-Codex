package com.theoriacodex.data.android.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.data.repository.CodexLikesTransactions
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.LibraryRepositoryContract
import com.theoriacodex.data.repository.LikesRepository
import com.theoriacodex.data.repository.RecentsRepository
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomLibraryContractTest : LibraryRepositoryContract() {
    private lateinit var database: TheoriaRoomDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            TheoriaRoomDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = database.close()

    override fun createCodexRepository(): CodexRepository = RoomCodexLikesRepository(database)

    override fun transactionsFor(repository: CodexRepository): CodexLikesTransactions =
        repository as RoomCodexLikesRepository

    override fun likesFor(transactions: CodexLikesTransactions): LikesRepository =
        transactions as RoomCodexLikesRepository

    override fun createRecentsRepository(clock: () -> Long, watchedLimit: Int, searchLimit: Int): RecentsRepository =
        RoomRecentsRepository(database, watchedLimit, searchLimit, clock)
}
