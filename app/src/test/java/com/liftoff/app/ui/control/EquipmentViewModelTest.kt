package com.liftoff.app.ui.control

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EquipmentViewModelTest {

    private lateinit var db: com.liftoff.app.data.LiftoffDatabase
    private lateinit var dao: com.liftoff.app.data.EquipmentDao
    private lateinit var vm: EquipmentViewModel
    private var scopeJob = Job()

    @After
    fun tearDown() {
        scopeJob.cancel()
        db.close()
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun createDbAndVm(): EquipmentViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, com.liftoff.app.data.LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.equipmentDao()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined + scopeJob)
        vm = EquipmentViewModel(dao, scope)
        return vm
    }

    @Suppress("SameParameterValue")
    private fun awaitState(predicate: (com.liftoff.app.ui.control.EquipmentState) -> Boolean) = runBlocking {
        try {
            withTimeout(5_000L) {
                vm.state.first(predicate)
            }
        } catch (_: TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for state predicate: $predicate")
        }
    }

    @Test
    fun invalidKeyPatternSetsError() = runBlocking {
        val badKeys = listOf("Bar", "pull up", "kb-24", "")
        for (key in badKeys) {
            vm = createDbAndVm()
            vm.startAdd()
            vm.setAddKey(key)
            vm.setAddName("Some name")
            vm.submitAdd()

            assertNotNull(vm.state.value.addKeyError)
            // Nothing should have been written
            val all = dao.observeAll().first()
            assertEquals(0, all.size)
        }
    }

    @Test
    fun duplicateAgainstActiveRejected() = runBlocking {
        vm = createDbAndVm()
        vm.startAdd()
        vm.setAddKey("barbell")
        vm.setAddName("Barbell")
        vm.submitAdd()
        awaitState { it.active.size == 1 && !it.adding }

        // Try to add a duplicate
        vm.startAdd()
        vm.setAddKey("barbell")
        vm.setAddName("Another barbell")
        vm.submitAdd()

        awaitState { s -> s.addKeyError != null && s.active.size == 1 }
        assertNotNull(vm.state.value.addKeyError)
        assertEquals(1, dao.observeAll().first().size)
    }

    @Test
    fun duplicateAgainstInactiveRejected() = runBlocking {
        vm = createDbAndVm()
        // Add and deactivate an item
        vm.startAdd()
        vm.setAddKey("barbell")
        vm.setAddName("Barbell")
        vm.submitAdd()
        awaitState { it.active.size == 1 && !it.adding }

        val id = dao.observeAll().first()[0].id
        vm.deactivate(id)
        awaitState { it.deactivated.size == 1 && it.active.isEmpty() }

        // Try to add the same key again
        vm.startAdd()
        vm.setAddKey("barbell")
        vm.setAddName("Another barbell")
        vm.submitAdd()

        awaitState { s -> s.addKeyError != null && s.active.isEmpty() }
        assertNotNull(vm.state.value.addKeyError)
        assertTrue(vm.state.value.addKeyError!!.contains("deactivated"))
        assertEquals(1, dao.observeAll().first().size)
    }

    @Test
    fun nameRequiredOnAdd() = runBlocking {
        vm = createDbAndVm()
        val blankNames = listOf("", "   ", "\t")
        for (name in blankNames) {
            vm.startAdd()
            vm.setAddKey("dumbbells")
            vm.setAddName(name)
            vm.submitAdd()

            assertNotNull(vm.state.value.addNameError)
            assertEquals(0, dao.observeAll().first().size)
        }
    }

    @Test
    fun successfulAdd() = runBlocking {
        vm = createDbAndVm()
        vm.startAdd()
        vm.setAddKey("dumbbells_2")
        vm.setAddName("Dumbbells 2kg")
        vm.setAddNotes("light ones")
        vm.submitAdd()

        awaitState { s -> s.active.size == 1 && !s.adding }

        val item = dao.observeAll().first()[0]
        assertEquals("dumbbells_2", item.key)
        assertEquals("Dumbbells 2kg", item.name)
        assertEquals("light ones", item.notes)
    }

    @Test
    fun cancelAddDiscardsDraft() = runBlocking {
        vm = createDbAndVm()
        vm.startAdd()
        vm.setAddKey("dumbbells")
        vm.setAddName("Dumbbells")
        vm.setAddNotes("some notes")
        vm.cancelAdd()

        val s = vm.state.value
        assertFalse(s.adding)
        assertEquals("", s.addKey)
        assertEquals("", s.addName)
        assertEquals("", s.addNotes)
        assertNull(s.addKeyError)
        assertNull(s.addNameError)
        assertEquals(0, dao.observeAll().first().size)

        // Reopen should show empty fields
        vm.startAdd()
        val s2 = vm.state.value
        assertTrue(s2.adding)
        assertEquals("", s2.addKey)
    }

    @Test
    fun startEditOnAnotherItemDiscardsFirstDraft() = runBlocking {
        vm = createDbAndVm()
        // Add two items
        vm.startAdd()
        vm.setAddKey("barbell")
        vm.setAddName("Barbell")
        vm.submitAdd()
        awaitState { it.active.size == 1 && !it.adding }

        vm.startAdd()
        vm.setAddKey("dumbbells")
        vm.setAddName("Dumbbells")
        vm.submitAdd()
        awaitState { s -> s.active.size == 2 && !s.adding }

        // Start editing first item
        val firstItem = dao.observeAll().first()[0]
        vm.startEdit(firstItem)
        vm.setEditName("Changed name")

        // Start editing second item — should replace first draft
        val secondItem = dao.observeAll().first()[1]
        vm.startEdit(secondItem)

        // editingId should now be the second item's id
        assertEquals(secondItem.id, vm.state.value.editingId)
        // And the name should be from the second item, not the changed first
        assertEquals(secondItem.name, vm.state.value.editName)

        // Verify first item is unchanged in DB
        val stillFirst = dao.get(firstItem.id)!!
        assertEquals(firstItem.name, stillFirst.name)
    }

    @Test
    fun editPersistsNameAndNotes() = runBlocking {
        vm = createDbAndVm()
        vm.startAdd()
        vm.setAddKey("barbell")
        vm.setAddName("Old Name")
        vm.setAddNotes("old notes")
        vm.submitAdd()
        awaitState { it.active.size == 1 && !it.adding }

        val item = dao.observeAll().first()[0]
        val id = item.id
        val originalKey = item.key

        // Start editing
        vm.startEdit(item)
        vm.setEditName("New Name")
        vm.setEditNotes("new notes")
        vm.submitEdit()

        awaitState { s -> s.editingId == null }

        // Check DB
        val updated = dao.get(id)!!
        assertEquals(originalKey, updated.key)
        assertEquals("New Name", updated.name)
        assertEquals("new notes", updated.notes)

        // Check state
        assertTrue(vm.state.value.active.any { it.id == id && it.name == "New Name" })
    }

    @Test
    fun nameRequiredOnEdit() = runBlocking {
        vm = createDbAndVm()
        vm.startAdd()
        vm.setAddKey("barbell")
        vm.setAddName("Barbell")
        vm.submitAdd()
        awaitState { it.active.size == 1 && !it.adding }

        val item = dao.observeAll().first()[0]
        vm.startEdit(item)
        vm.setEditName("")
        vm.submitEdit()

        assertNotNull(vm.state.value.editNameError)
        // DB should be unchanged
        val stillThere = dao.get(item.id)!!
        assertEquals("Barbell", stillThere.name)
    }

    @Test
    fun deactivateMovesToLists() = runBlocking {
        vm = createDbAndVm()
        vm.startAdd()
        vm.setAddKey("barbell")
        vm.setAddName("Barbell")
        vm.submitAdd()
        awaitState { it.active.size == 1 && !it.adding }

        val item = dao.observeAll().first()[0]
        val id = item.id

        vm.deactivate(id)
        awaitState { s -> s.active.isEmpty() && s.deactivated.size == 1 }

        // showDeactivated is false by default, but deactivated data is still in state
        assertTrue(vm.state.value.showDeactivated.not())

        val deactivated = vm.state.value.deactivated[0]
        assertEquals("barbell", deactivated.key)
        assertEquals("Barbell", deactivated.name)

        // Still in observeAll()
        assertEquals(1, dao.observeAll().first().size)
    }

    @Test
    fun reactivateReturnsToActive() = runBlocking {
        vm = createDbAndVm()
        vm.startAdd()
        vm.setAddKey("barbell")
        vm.setAddName("Barbell")
        vm.submitAdd()
        awaitState { it.active.size == 1 && !it.adding }

        val item = dao.observeAll().first()[0]
        val id = item.id

        vm.deactivate(id)
        awaitState { it.active.isEmpty() }
        vm.setShowDeactivated(true)
        awaitState { it.deactivated.size == 1 }

        vm.reactivate(id)
        awaitState { s -> s.active.size == 1 && s.deactivated.isEmpty() }

        val activeItem = vm.state.value.active[0]
        assertEquals("barbell", activeItem.key)
        assertEquals("Barbell", activeItem.name)
    }

    @Test
    fun errorClearsOnTyping() = runBlocking {
        vm = createDbAndVm()
        vm.startAdd()
        vm.setAddKey("Bar")
        vm.setAddName("Name")
        vm.submitAdd()

        assertNotNull(vm.state.value.addKeyError)

        // Typing a valid key clears the error
        vm.setAddKey("barbell")
        assertNull(vm.state.value.addKeyError)
    }

    @Test
    fun whitespaceTrimmedOnAdd() = runBlocking {
        vm = createDbAndVm()
        vm.startAdd()
        vm.setAddKey("  barbell  ")
        vm.setAddName("  Barbell  ")
        vm.setAddNotes("  notes  ")
        vm.submitAdd()

        awaitState { it.active.size == 1 && !it.adding }

        val item = dao.observeAll().first()[0]
        assertEquals("barbell", item.key)
        assertEquals("Barbell", item.name)
        assertEquals("notes", item.notes)
    }

    @Test
    fun whitespaceTrimmedOnEdit() = runBlocking {
        vm = createDbAndVm()
        vm.startAdd()
        vm.setAddKey("barbell")
        vm.setAddName("Barbell")
        vm.submitAdd()
        awaitState { it.active.size == 1 && !it.adding }

        val item = dao.observeAll().first()[0]
        vm.startEdit(item)
        vm.setEditName("  New Name  ")
        vm.setEditNotes("  new notes  ")
        vm.submitEdit()

        awaitState { it.editingId == null }

        val updated = dao.get(item.id)!!
        assertEquals("New Name", updated.name)
        assertEquals("new notes", updated.notes)
    }

    @Test
    fun showDeactivatedStartsOff() = runBlocking {
        // First VM turns it on
        vm = createDbAndVm()
        vm.setShowDeactivated(true)
        awaitState { it.showDeactivated }

        // Second VM on same DB should start with false
        val context = ApplicationProvider.getApplicationContext<Context>()
        db.close()
        db = Room.inMemoryDatabaseBuilder(context, com.liftoff.app.data.LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.equipmentDao()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined + scopeJob)
        vm = EquipmentViewModel(dao, scope)

        assertFalse(vm.state.value.showDeactivated)
    }

    @Test
    fun changesPersistAcrossViewModels() = runBlocking {
        // First VM: add, edit, deactivate
        vm = createDbAndVm()
        vm.startAdd()
        vm.setAddKey("barbell")
        vm.setAddName("Barbell")
        vm.submitAdd()
        awaitState { it.active.size == 1 && !it.adding }

        val item = dao.observeAll().first()[0]
        val id = item.id

        // Edit
        vm.startEdit(item)
        vm.setEditName("Updated Barbell")
        vm.setEditNotes("updated notes")
        vm.submitEdit()
        awaitState { it.editingId == null }

        // Deactivate
        vm.deactivate(id)
        awaitState { it.active.isEmpty() }

        // Create a new VM on the same DB — should see the same data
        val scope2 = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined + scopeJob)
        vm = EquipmentViewModel(dao, scope2)

        // New VM should see the changes — deactivated list is always populated
        awaitState { s -> s.active.isEmpty() && s.deactivated.size == 1 }

        val deactivatedItem = vm.state.value.deactivated[0]
        assertEquals(id, deactivatedItem.id)
        assertEquals("barbell", deactivatedItem.key)
        assertEquals("Updated Barbell", deactivatedItem.name)
        assertEquals("updated notes", deactivatedItem.notes)
    }
}
