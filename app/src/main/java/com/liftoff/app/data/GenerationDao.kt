package com.liftoff.app.data

import androidx.room.*

@Dao
interface GenerationDao {

    @Insert
    suspend fun insert(generation: Generation): Long

    @Query("SELECT * FROM generation WHERE id = :id")
    suspend fun get(id: Long): Generation?

    // The export's whole-table read.
    @Query("SELECT * FROM generation ORDER BY id")
    suspend fun getAll(): List<Generation>

    // Import's bulk insert and emptiness read; a generation row points at a Mission, so it goes in last.
    @Insert
    suspend fun insertAll(generations: List<Generation>)

    @Query("SELECT COUNT(*) FROM generation")
    suspend fun countAll(): Long

    @Update
    suspend fun update(generation: Generation)

    @Delete
    suspend fun delete(generation: Generation)

    // The generation worker's row helpers. Each one writes only the columns that step owns, so a row that is
    // restarted keeps its session, its event cursor and its attempt count.

    @Query("UPDATE generation SET status = 'RUNNING' WHERE id = :id")
    suspend fun markRunning(id: Long)

    @Query("UPDATE generation SET status = 'SUCCEEDED', finishedAt = :finishedAt WHERE id = :id")
    suspend fun markSucceeded(id: Long, finishedAt: Long)

    // A generation that failed has still finished, so it stamps finishedAt too and keeps the reason it failed for.
    @Query("UPDATE generation SET status = 'FAILED', error = :error, finishedAt = :finishedAt WHERE id = :id")
    suspend fun markFailed(id: Long, error: String, finishedAt: Long)

    @Query("UPDATE generation SET status = 'CANCELLED', finishedAt = :finishedAt WHERE id = :id")
    suspend fun markCancelled(id: Long, finishedAt: Long)

    @Query("UPDATE generation SET nabuSessionId = :nabuSessionId WHERE id = :id")
    suspend fun saveSessionId(id: Long, nabuSessionId: String)

    // The cursor moves as events arrive, so it is saved on its own rather than travelling with the session id.
    @Query("UPDATE generation SET lastEventId = :lastEventId WHERE id = :id")
    suspend fun saveLastEventId(id: Long, lastEventId: String)

    // The row counts its own attempts; the worker never says which number it is on.
    @Query("UPDATE generation SET attempt = attempt + 1 WHERE id = :id")
    suspend fun bumpAttempt(id: Long)

    // The offline mark travels on error alone, and only while the row is still waiting on the coach: a row that
    // has already decided its outcome keeps the reason it failed for. Passing null clears the mark.
    @Query("UPDATE generation SET error = :error WHERE id = :id AND status IN ('QUEUED', 'RUNNING')")
    suspend fun setCoachOfflineMark(id: Long, error: String?)

    // Newest by createdAt, tie-break id: the table records no activity, and a cancelled row is a dead end that
    // must not stand in as the current generation.
    @Query(
        "SELECT * FROM generation WHERE sortieId = :sortieId AND status != 'CANCELLED' " +
            "ORDER BY createdAt DESC, id DESC LIMIT 1"
    )
    suspend fun getNewestNonCancelledForSortie(sortieId: Long): Generation?

    // Only the Mission's own outline generation: a sortie's plan generation is not what the draft screen shows.
    @Query(
        "SELECT * FROM generation WHERE missionId = :missionId AND kind = 'OUTLINE' AND status != 'CANCELLED' " +
            "ORDER BY createdAt DESC, id DESC LIMIT 1"
    )
    suspend fun getNewestNonCancelledOutlineForMission(missionId: Long): Generation?
}
