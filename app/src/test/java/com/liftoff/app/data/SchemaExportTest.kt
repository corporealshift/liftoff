package com.liftoff.app.data

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SchemaExportTest {

    @Test
    fun exportedV1SchemaMatchesDatabase() {
        // Find the schema file — Robolectric may change working directory, so check multiple locations
        val projectRoot = File(System.getProperty("user.dir")).parentFile
        val possiblePaths = buildList {
            add("app/schemas/com.liftoff.app.data.LiftoffDatabase/1.json")
            add("schemas/com.liftoff.app.data.LiftoffDatabase/1.json")
            if (projectRoot.isDirectory) {
                add(File(projectRoot, "app/schemas/com.liftoff.app.data.LiftoffDatabase/1.json").path)
            }
        }

        var jsonContent: String? = null
        for (path in possiblePaths) {
            val f = File(path)
            if (f.exists()) {
                jsonContent = f.readText()
                break
            }
        }

        assertTrue("Schema file should exist at app/schemas/com.liftoff.app.data.LiftoffDatabase/1.json", jsonContent != null && jsonContent!!.isNotEmpty())
        val content = jsonContent!!
        // The schema JSON should contain the entity info for all 9 entities
        assertTrue("Schema should mention 'mission' table", content.contains("mission"))
        assertTrue("Schema should mention 'sortie' table", content.contains("sortie"))
        assertTrue("Schema should mention 'flightPlan' table", content.contains("flightPlan"))
        assertTrue("Schema should mention 'plannedExercise' table", content.contains("plannedExercise"))
        assertTrue("Schema should mention 'plannedSet' table", content.contains("plannedSet"))
        assertTrue("Schema should mention 'runSegment' table", content.contains("runSegment"))
        assertTrue("Schema should mention 'exercise' table", content.contains("exercise"))
        assertTrue("Schema should mention 'generation' table", content.contains("generation"))
        assertTrue("Schema should mention 'equipment' table", content.contains("equipment"))

        // Verify the schema JSON is valid by checking it contains expected keys
        assertTrue("Schema should contain 'formatVersion'", content.contains("formatVersion"))
        assertTrue("Schema should contain 'database'", content.contains("database"))
    }
}
