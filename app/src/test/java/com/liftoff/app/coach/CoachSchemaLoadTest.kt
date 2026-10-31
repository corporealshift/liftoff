package com.liftoff.app.coach

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The schema model and its classpath loader, over the three real schema files.
 *
 * Plain JVM test: it touches only the classpath and a JSON parser, so no Robolectric.
 * The bad schemas are stated inline and handed to `parse`, so no test resource file is needed.
 */
class CoachSchemaLoadTest {

    @Test
    fun outlineSchemaFileLoadsThroughTheLoader() {
        val schema = CoachSchemas.load(CoachSchemas.OUTLINE)

        assertEquals(CoachSchemas.OUTLINE, schema.name)
        assertEquals(SchemaType.OBJECT, schema.type)
        assertEquals(false, schema.additionalProperties)
        assertEquals(listOf("sorties", "notes"), schema.required)
        assertEquals(listOf("sorties", "notes"), schema.properties.keys.toList())

        val sorties = schema.properties.getValue("sorties")
        assertEquals(SchemaType.ARRAY, sorties.type)
        val sortie = sorties.items
        assertNotNull("outline sorties has no items schema", sortie)
        assertEquals(listOf("index", "type", "focus"), sortie!!.required)
        assertEquals(0, sortie.properties.getValue("index").minimum!!.content.toInt())
        assertEquals(listOf("lift", "run"), sortie.properties.getValue("type").enumValues!!.map { it.jsonText() })
        assertEquals(60, sortie.properties.getValue("focus").maxLength!!)

        assertEquals(1000, schema.properties.getValue("notes").maxLength!!)
    }

    @Test
    fun liftPlanSchemaFileLoadsThroughTheLoader() {
        val schema = CoachSchemas.load(CoachSchemas.LIFT_PLAN)

        assertEquals(CoachSchemas.LIFT_PLAN, schema.name)
        assertEquals(SchemaType.OBJECT, schema.type)
        assertEquals(false, schema.additionalProperties)
        assertEquals(
            listOf("type", "title", "estimated_minutes", "exercises", "notes"),
            schema.required,
        )
        assertEquals("lift", schema.properties.getValue("type").constValue!!.jsonText())
        assertEquals(10, schema.properties.getValue("estimated_minutes").minimum!!.content.toInt())
        assertEquals(180, schema.properties.getValue("estimated_minutes").maximum!!.content.toInt())

        val exercises = schema.properties.getValue("exercises")
        assertEquals(3, exercises.minItems!!)
        assertEquals(10, exercises.maxItems!!)
        val exercise = exercises.items
        assertNotNull("lift-plan exercises has no items schema", exercise)
        assertEquals(listOf("name", "equipment", "sets"), exercise!!.required)

        val sets = exercise.properties.getValue("sets")
        assertEquals(1, sets.minItems!!)
        assertEquals(8, sets.maxItems!!)
        val set = sets.items
        assertNotNull("lift-plan sets has no items schema", set)
        assertEquals(1, set!!.properties.getValue("reps").minimum!!.content.toInt())
        assertEquals(50, set.properties.getValue("reps").maximum!!.content.toInt())
        assertEquals(SchemaType.NUMBER, set.properties.getValue("weight").type)
        // The set object declares no `required`; the model must not invent one.
        assertEquals(emptyList<String>(), set.required)
    }

    @Test
    fun runPlanSchemaFileLoadsThroughTheLoader() {
        val schema = CoachSchemas.load(CoachSchemas.RUN_PLAN)

        assertEquals(CoachSchemas.RUN_PLAN, schema.name)
        assertEquals(SchemaType.OBJECT, schema.type)
        assertEquals(false, schema.additionalProperties)
        assertEquals(listOf("type", "title", "kind", "segments", "notes"), schema.required)
        assertEquals("run", schema.properties.getValue("type").constValue!!.jsonText())
        assertEquals(
            listOf("easy", "tempo", "intervals", "long", "recovery"),
            schema.properties.getValue("kind").enumValues!!.map { it.jsonText() },
        )

        // A bound written as a decimal keeps the text the schema wrote, so 0.5 prints as 0.5.
        assertEquals("0.5", schema.properties.getValue("target_distance").minimum!!.content)
        assertEquals(50, schema.properties.getValue("target_distance").maximum!!.content.toInt())

        val segments = schema.properties.getValue("segments")
        assertEquals(1, segments.minItems!!)
        assertEquals(20, segments.maxItems!!)
        val segment = segments.items
        assertNotNull("run-plan segments has no items schema", segment)
        assertEquals(listOf("description"), segment!!.required)
        assertEquals(120, segment.properties.getValue("description").maxLength!!)
    }

    @Test
    fun theThreeResourcePathsAreThePathsTheSchemaFilesSitAt() {
        assertEquals(
            listOf("schemas/outline.json", "schemas/lift-plan.json", "schemas/run-plan.json"),
            CoachSchemas.RESOURCE_PATHS,
        )
        for (resource in CoachSchemas.RESOURCE_PATHS) {
            assertEquals(resource, CoachSchemas.load(resource).name)
        }
    }

    /**
     * Walks every schema node in all three files and asserts each keyword found is one the model
     * implements. A schema edited later to add a keyword fails here, in the gate, not at
     * generation time.
     */
    @Test
    fun everyKeywordInTheThreeFilesIsSupportedByTheModel() {
        val used = linkedSetOf<String>()
        for (resource in CoachSchemas.RESOURCE_PATHS) {
            collectKeywords(Json.parseToJsonElement(readResource(resource)), used)
        }

        val unsupported = used.filter { it !in CoachSchemas.SUPPORTED_KEYWORDS }.sorted()
        assertEquals(
            "keywords used by the schema files but not implemented by the model: $unsupported",
            emptyList<String>(),
            unsupported,
        )
        // The walk reached every nested node, so the assertion above is not vacuous.
        val missing = CoachSchemas.SUPPORTED_KEYWORDS.filter { it !in used }.sorted()
        assertEquals("keywords the model supports that no schema file uses: $missing", emptyList<String>(), missing)
    }

    @Test
    fun unsupportedKeywordIsRejectedAndNamesTheFileAndKeyword() {
        assertRejected(
            "inline-anyof.json",
            """{"type":"object","anyOf":[{"type":"string"},{"type":"null"}]}""",
            "inline-anyof.json",
            "anyOf",
        )
        // A keyword buried in a nested schema is caught too, not just a top-level one.
        assertRejected(
            "inline-nested-pattern.json",
            """{"type":"object","properties":{"title":{"type":"string","pattern":"^x"}}}""",
            "inline-nested-pattern.json",
            "pattern",
        )
    }

    @Test
    fun nonBooleanAdditionalPropertiesIsRejectedAndNamesTheFileAndKeyword() {
        assertRejected(
            "inline-schema-object.json",
            """{"type":"object","additionalProperties":{"type":"string"}}""",
            "inline-schema-object.json",
            "additionalProperties",
        )
        assertRejected(
            "inline-string-flag.json",
            """{"type":"object","additionalProperties":"false"}""",
            "inline-string-flag.json",
            "additionalProperties",
        )
    }

    @Test
    fun typeWrittenAsAListIsRejectedAndNamesTheFileAndKeyword() {
        assertRejected(
            "inline-type-union.json",
            """{"type":["string","null"]}""",
            "inline-type-union.json",
            "type",
        )
        assertRejected(
            "inline-unknown-type.json",
            """{"type":"integerish"}""",
            "inline-unknown-type.json",
            "type",
        )
    }

    @Test
    fun malformedSchemaIsRejectedAndNamesTheFile() {
        assertRejected(
            "inline-truncated.json",
            "{\"type\":\"object\",\"properties\":{\"name\":",
            "inline-truncated.json",
        )
        assertRejected("inline-not-an-object.json", """["type","object"]""", "inline-not-an-object.json")
        assertRejected(
            "inline-bad-bound.json",
            """{"type":"string","maxLength":"1000"}""",
            "inline-bad-bound.json",
            "maxLength",
        )
        assertRejected(
            "inline-required-not-a-list.json",
            """{"type":"object","required":"notes"}""",
            "inline-required-not-a-list.json",
            "required",
        )
    }

    @Test
    fun missingResourceIsRejectedAndNamesTheFile() {
        val error = assertThrows(UnsupportedSchemaException::class.java) {
            CoachSchemas.load("schemas/not-a-schema.json")
        }
        assertTrue("message was: ${error.message}", error.message!!.contains("schemas/not-a-schema.json"))
    }

    @Test
    fun aSchemaWithoutTypeOrBoundsCarriesNoInventedRules() {
        val schema = CoachSchemas.parse("inline-bare.json", """{"const":"lift"}""")

        assertEquals("inline-bare.json", schema.name)
        assertNull(schema.type)
        assertNull(schema.additionalProperties)
        assertEquals(emptyList<String>(), schema.required)
        assertEquals("lift", schema.constValue!!.jsonText())
    }

    private fun assertRejected(name: String, text: String, vararg mustContain: String) {
        val error = assertThrows(UnsupportedSchemaException::class.java) {
            CoachSchemas.parse(name, text)
        }
        val message = error.message ?: "no message"
        for (part in mustContain) {
            assertTrue("$name: message was \"$message\", expected it to name \"$part\"", message.contains(part))
        }
    }

    /** Only a schema node's own keys are keywords; the keys of `properties` are property names. */
    private fun collectKeywords(node: JsonElement, into: MutableSet<String>) {
        if (node !is JsonObject) return
        for ((key, value) in node) {
            into += key
            when (key) {
                "properties" -> if (value is JsonObject) value.values.forEach { collectKeywords(it, into) }
                "items" -> collectKeywords(value, into)
            }
        }
    }

    private fun readResource(resource: String): String =
        javaClass.classLoader!!.getResourceAsStream(resource)!!.use { it.readBytes().toString(Charsets.UTF_8) }

    /** A JSON string reads as `lift`; anything else keeps its own JSON text. */
    private fun JsonElement.jsonText(): String =
        if (this is JsonPrimitive && isString) content else toString()
}
