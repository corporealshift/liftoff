package com.liftoff.app.ui.control

import androidx.annotation.VisibleForTesting
import com.liftoff.app.data.Equipment
import com.liftoff.app.data.EquipmentDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** UI state for the equipment section. */
data class EquipmentState(
    val active: List<Equipment> = emptyList(),
    val deactivated: List<Equipment> = emptyList(),
    val showDeactivated: Boolean = false,
    val adding: Boolean = false,
    val addKey: String = "",
    val addName: String = "",
    val addNotes: String = "",
    val addKeyError: String? = null,
    val addNameError: String? = null,
    val editingId: Long? = null,
    val editName: String = "",
    val editNotes: String = "",
    val editNameError: String? = null
)

/** Plain-Kotlin state holder for equipment management. */
class EquipmentViewModel(
    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    val dao: EquipmentDao,
    private val scope: CoroutineScope
) {

    private val _state = MutableStateFlow(EquipmentState())
    val state: StateFlow<EquipmentState> = _state

    init {
        // Load initial data from the database
        scope.launch {
            try {
                reloadState()
            } catch (_: Exception) {
                // If initial load fails, leave state as defaults
            }
        }
    }

    // ── Add form ────────────────────────────────────────────────────────

    fun startAdd() {
        _state.value = _state.value.copy(adding = true)
    }

    fun cancelAdd() {
        val s = _state.value
        _state.value = s.copy(adding = false, addKey = "", addName = "", addNotes = "", addKeyError = null, addNameError = null)
    }

    fun setAddKey(value: String) {
        _state.value = _state.value.copy(addKey = value, addKeyError = null)
    }

    fun setAddName(value: String) {
        _state.value = _state.value.copy(addName = value, addNameError = null)
    }

    fun setAddNotes(value: String) {
        _state.value = _state.value.copy(addNotes = value)
    }

    fun submitAdd() {
        val s = _state.value
        val key = s.addKey.trim()
        val name = s.addName.trim()
        val notes = s.addNotes.trim()

        // 1. Validate key pattern
        if (!VALID_KEY_REGEX.matches(key)) {
            _state.value = s.copy(addKeyError = "Key must use only a–z, 0–9 and _")
            return
        }

        // 2. Name required
        if (name.isBlank()) {
            _state.value = s.copy(addNameError = "Name is required")
            return
        }

        // 3. Check for duplicate key (active or inactive) — synchronous read
        val all = try { kotlinx.coroutines.runBlocking { dao.observeAll().first() } } catch (_: Exception) { emptyList<Equipment>() }
        val existing = all.find { it.key == key }
        if (existing != null) {
            val msg = if (!existing.active) {
                "Key \"$key\" is already used by a deactivated item"
            } else {
                "Key \"$key\" is already used"
            }
            _state.value = s.copy(addKeyError = msg)
            return
        }

        // 4. Insert — launch the write on the scope
        scope.launch {
            try {
                dao.add(Equipment(key = key, name = name, notes = notes))
                reloadState()
                _state.value = _state.value.copy(adding = false)
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                // Race backstop for unique index violation
                _state.value = s.copy(addKeyError = "Key \"$key\" is already used")
            }
        }
    }

    // ── Edit form ───────────────────────────────────────────────────────

    fun startEdit(item: Equipment) {
        _state.value = _state.value.copy(
            editingId = item.id,
            editName = item.name,
            editNotes = item.notes,
            editNameError = null
        )
    }

    fun cancelEdit() {
        _state.value = _state.value.copy(editingId = null, editName = "", editNotes = "", editNameError = null)
    }

    fun setEditName(value: String) {
        _state.value = _state.value.copy(editName = value, editNameError = null)
    }

    fun setEditNotes(value: String) {
        _state.value = _state.value.copy(editNotes = value)
    }

    fun submitEdit() {
        val s = _state.value
        val id = s.editingId ?: return
        val name = s.editName.trim()

        if (name.isBlank()) {
            _state.value = s.copy(editNameError = "Name is required")
            return
        }

        val notes = s.editNotes.trim()
        scope.launch {
            dao.edit(id, name, notes)
            reloadState()
            _state.value = _state.value.copy(editingId = null)
        }
    }

    // ── Deactivate / Reactivate ─────────────────────────────────────────

    fun deactivate(id: Long) {
        scope.launch {
            dao.deactivate(id)
            reloadState()
        }
    }

    fun reactivate(id: Long) {
        scope.launch {
            dao.reactivate(id)
            reloadState()
        }
    }

    // ── Toggle deactivated view ─────────────────────────────────────────

    fun setShowDeactivated(value: Boolean) {
        _state.value = _state.value.copy(showDeactivated = value)
    }

    // ── Internal ────────────────────────────────────────────────────────

    private suspend fun reloadState() {
        val all = dao.observeAll().first()
        val active = all.filter { it.active }
        val deactivated = all.filterNot { it.active }
        _state.value = _state.value.copy(
            active = active,
            deactivated = deactivated
        )
    }
}

private val VALID_KEY_REGEX = Regex("[a-z0-9_]+")
