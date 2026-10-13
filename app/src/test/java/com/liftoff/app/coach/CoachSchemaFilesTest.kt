package com.liftoff.app.coach

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Loads the three coach output schemas off the unit-test classpath.
 *
 * Plain JVM test: it touches only the classpath and a JSON parser, so no Robolectric.
 * AGP puts app/src/main/resources on testDebugUnitTest's classpath via processDebugJavaRes,
 * which is what this test exists to prove.
 */
class CoachSchemaFilesTest {

    // Each test names its own resource path in the failure message, so a missing file reads
    // as a missing file rather than a null trace.
    @Test
    fun outlineSchemaLoadsFromTestClasspath() {
        assertSchemaIsAnObjectWithRequiredFields("schemas/outline.json")
    }

    @Test
    fun liftPlanSchemaLoadsFromTestClasspath() {
        assertSchemaIsAnObjectWithRequiredFields("schemas/lift-plan.json")
    }

    @Test
    fun runPlanSchemaLoadsFromTestClasspath() {
        assertSchemaIsAnObjectWithRequiredFields("schemas/run-plan.json")
    }

    private fun assertSchemaIsAnObjectWithRequiredFields(resource: String) {
        val loader = javaClass.classLoader
        val text = loader?.getResourceAsStream(resource)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw AssertionError("$resource missing from the test classpath")

        val element = Json.parseToJsonElement(text)
        assertTrue("$resource is not a JSON object", element is JsonObject)

        val schema = element.jsonObject
        val type = schema["type"]?.jsonPrimitive
        assertNotNull("$resource has no \"type\"", type)
        assertEquals("$resource \"type\" is not \"object\"", "object", type!!.content)

        val required = schema["required"]
        assertNotNull("$resource has no \"required\"", required)
        val requiredList = required!!.jsonArray
        assertTrue("$resource \"required\" list is empty", requiredList.isNotEmpty())
    }
}
