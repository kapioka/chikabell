package com.chikabell.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chikabell.app.data.database.AppDatabase
import com.chikabell.app.data.repository.LocationRepositoryImpl
import com.chikabell.app.domain.model.LocationDraft
import com.chikabell.app.domain.model.SourceType
import com.chikabell.app.importexport.LocationImportCandidate
import com.chikabell.app.importexport.LocationImportCommitter
import com.chikabell.app.importexport.ImportReviewChanged
import com.chikabell.app.importexport.TransferFormat
import com.chikabell.app.geofence.ReconcileResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportTransactionDeviceTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: LocationRepositoryImpl
    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // All records use an in-memory database; no production DB is opened by this test.
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = LocationRepositoryImpl(database.locationDao(), database)
    }
    @After fun tearDown() { if (::database.isInitialized) database.close() }
    private fun draft(name: String, tags: List<String> = emptyList()) = LocationDraft(
        name = name, message = "fictional QA", latitude = 35.0, longitude = 135.0, radiusMeters = 300,
        loiteringDelaySeconds = 60, cooldownMinutes = 720, enabled = false, sourceType = SourceType.JSON_IMPORT, tags = tags,
    )
    @Test fun rollsBackEntireImportWhenLaterTagsAreInvalid() = runBlocking {
        repository.addLocation(draft("existing"))
        val candidates = listOf(LocationImportCandidate(null, draft("valid")), LocationImportCandidate(null, draft("invalid", (1..6).map { "tag$it" })))
        var failed = false
        try { repository.addImportedLocations(candidates) } catch (_: IllegalStateException) { failed = true }
        Assert.assertTrue(failed)
        Assert.assertEquals(listOf("existing"), repository.observeLocations().first().map { it.name })
        Assert.assertEquals(0, database.locationDao().countTags())
    }
    @Test fun rollsBackWhenGlobalTagBudgetIsExceeded() = runBlocking {
        repeat(10) { row -> repository.addLocation(draft("existing$row", (0..4).map { "tag" + (row * 5 + it) })) }
        Assert.assertEquals(50, database.locationDao().countTags())
        var failed = false
        try { repository.addImportedLocations(listOf(LocationImportCandidate(null, draft("new", listOf("new-tag"))))) } catch (_: IllegalStateException) { failed = true }
        Assert.assertTrue(failed)
        Assert.assertEquals(10, repository.observeLocations().first().size)
        Assert.assertEquals(50, database.locationDao().countTags())
    }
    @Test fun duplicateIdDoesNotOverwriteExistingLocation() = runBlocking {
        repository.addLocation(draft("existing"))
        val id = repository.observeLocations().first().single().id
        var failed = false
        try { repository.addImportedLocations(listOf(LocationImportCandidate(id, draft("replacement")))) } catch (_: Exception) { failed = true }
        Assert.assertTrue(failed)
        Assert.assertEquals("existing", repository.observeLocations().first().single().name)
    }

    @Test fun transactionRechecksDuplicatesWithoutWritingAnySelectedCandidate() = runBlocking {
        repository.addLocation(draft("existing"))
        try {
            repository.addImportedLocations(listOf(LocationImportCandidate(null, draft("new")), LocationImportCandidate(null, draft("existing"))))
            Assert.fail("Stale review must not be committed")
        } catch (changed: ImportReviewChanged) { Assert.assertEquals(1, changed.preview.duplicateCount) }
        Assert.assertEquals(listOf("existing"), repository.observeLocations().first().map { it.name })
    }

    @Test fun transactionRechecksEnabledLimit() = runBlocking {
        repeat(100) { repository.addLocation(draft("existing$it").copy(enabled = true)) }
        try {
            repository.addImportedLocations(listOf(LocationImportCandidate(null, draft("new").copy(enabled = true))))
            Assert.fail("Enabled limit must block the atomic save")
        } catch (changed: ImportReviewChanged) { Assert.assertTrue(changed.preview.errors.any { it.contains("100") }) }
        Assert.assertEquals(100, repository.observeLocations().first().size)
    }

    @Test fun sharedCommitterRetainsSuccessfulSaveWhenMonitoringThrows() = runBlocking {
        val committer = LocationImportCommitter(repository) { error("simulated monitoring failure") }
        val result = committer.commit(listOf(LocationImportCandidate(null, draft("new"))), TransferFormat.JSON)
        Assert.assertTrue(result.startsWith("1件を保存しました"))
        Assert.assertEquals(listOf("new"), repository.observeLocations().first().map { it.name })
    }

    @Test fun sharedCommitterRejectsStaleReviewBeforeMonitoring() = runBlocking {
        repository.addLocation(draft("existing"))
        var reconciled = false
        val committer = LocationImportCommitter(repository) { reconciled = true; ReconcileResult.Blocked("test") }
        try {
            committer.commit(listOf(LocationImportCandidate(null, draft("existing"))), TransferFormat.JSON)
            Assert.fail("Duplicate review must be returned")
        } catch (changed: ImportReviewChanged) { Assert.assertEquals(1, changed.preview.duplicateCount) }
        Assert.assertFalse(reconciled)
        Assert.assertEquals(1, repository.observeLocations().first().size)
    }
}
