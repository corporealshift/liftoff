package com.liftoff.app.coach

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * A coach output schema, parsed from one of the three resource files.
 *
 * The model covers exactly the 13 keywords the three files use, so a schema edited later to add a
 * keyword the checker does not implement fails at load instead of being silently ignored — the
 * phone would otherwise accept a document the schema forbids (DESIGN.md §15 decision 14).
 */
data class CoachSchema(
    /** The resource path or name the schema text came from, so a rejection names its file. */
    val name: String,
    /** null when the schema gives no `type`; the other keywords then stand on their own. */
    val type: SchemaType? = null,
    val constValue: JsonElement? = null,
    /** null when absent; an empty list is a schema error, not "matches nothing". */
    val enumValues: List<JsonElement>? = null,
    val required: List<String> = emptyList(),
    /** null means the schema omitted it, which JSON Schema reads as "extra properties allowed". */
    val additionalProperties: Boolean? = null,
    val minimum: JsonPrimitive? = null,
    val maximum: JsonPrimitive? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val minItems: Int? = null,
    val maxItems: Int? = null,
    /** In schema order, which is the order the checker walks them. */
    val properties: Map<String, CoachSchema> = emptyMap(),
    val items: CoachSchema? = null,
)

/** The only `type` values the three schema files use. */
enum class SchemaType {
    OBJECT,
    ARRAY,
    STRING,
    INTEGER,
    NUMBER,
    BOOLEAN,
    NULL,
    ;

    companion object {
        /** null when the word is not one of these, so the caller reports it against the file. */
        fun parseOrNull(word: String): SchemaType? =
            when (word) {
                "object" -> OBJECT
                "array" -> ARRAY
                "string" -> STRING
                "integer" -> INTEGER
                "number" -> NUMBER
                "boolean" -> BOOLEAN
                "null" -> NULL
                else -> null
            }
    }
}

/** Thrown for a schema this checker cannot read; the message names the file, and the keyword. */
class UnsupportedSchemaException(message: String) : RuntimeException(message)

/**
 * Reads a schema off the classpath, the way `CoachSchemaFilesTest` already reads the three files.
 *
 * The classpath loader is used rather than a build change because AGP already puts
 * `app/src/main/resources` on `testDebugUnitTest`'s classpath, which that test proves.
 */
object CoachSchemas {

    const val OUTLINE = "schemas/outline.json"
    const val LIFT_PLAN = "schemas/lift-plan.json"
    const val RUN_PLAN = "schemas/run-plan.json"

    val RESOURCE_PATHS = listOf(OUTLINE, LIFT_PLAN, RUN_PLAN)

    /** Every keyword this model implements; anything else in a schema file is rejected. */
    val SUPPORTED_KEYWORDS: Set<String> = setOf(
        "type",
        "const",
        "enum",
        "required",
        "additionalProperties",
        "minimum",
        "maximum",
        "minLength",
        "maxLength",
        "minItems",
        "maxItems",
        "properties",
        "items",
    )

    fun load(resource: String): CoachSchema {
        val stream = javaClass.classLoader?.getResourceAsStream(resource)
            ?: throw UnsupportedSchemaException("$resource: schema resource not found on the classpath")
        val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        return parse(resource, text)
    }

    fun parse(name: String, text: String): CoachSchema {
        val element = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw UnsupportedSchemaException("$name: not readable JSON (${e.message})")
        }
        if (element !is JsonObject) {
            throw UnsupportedSchemaException("$name: a schema must be a JSON object")
        }
        return schema(name, element)
    }

    private fun schema(name: String, node: JsonObject): CoachSchema {
        var type: SchemaType? = null
        var constValue: JsonElement? = null
        var enumValues: List<JsonElement>? = null
        var required: List<String> = emptyList()
        var additionalProperties: Boolean? = null
        var minimum: JsonPrimitive? = null
        var maximum: JsonPrimitive? = null
        var minLength: Int? = null
        var maxLength: Int? = null
        var minItems: Int? = null
        var maxItems: Int? = null
        var properties: Map<String, CoachSchema> = emptyMap()
        var items: CoachSchema? = null

        for ((key, value) in node) {
            when (key) {
                "type" -> {
                    // A `type` written as a list is JSON Schema's union form; the checker has no
                    // union to apply, so it is rejected rather than narrowed to the first entry.
                    if (value is JsonArray) {
                        throw UnsupportedSchemaException(
                            "$name: \"type\" written as a list is not supported"
                        )
                    }
                    val word = (value as? JsonPrimitive)?.contentOrNull
                    if (word == null) {
                        throw UnsupportedSchemaException("$name: \"type\" is not a type name")
                    }
                    type = SchemaType.parseOrNull(word)
                        ?: throw UnsupportedSchemaException(
                            "$name: \"type\": unsupported type '$word'"
                        )
                }
                "const" -> constValue = value
                "enum" -> {
                    if (value !is JsonArray) {
                        throw UnsupportedSchemaException("$name: \"enum\" is not a list")
                    }
                    if (value.isEmpty()) {
                        throw UnsupportedSchemaException("$name: \"enum\" is empty")
                    }
                    enumValues = value.toList()
                }
                "required" -> {
                    if (value !is JsonArray) {
                        throw UnsupportedSchemaException("$name: \"required\" is not a list")
                    }
                    required = value.map { item ->
                        (item as? JsonPrimitive)?.contentOrNull
                            ?: throw UnsupportedSchemaException(
                                "$name: \"required\" holds a name that is not a string"
                            )
                    }
                }
                "additionalProperties" -> {
                    // isString first: kotlinx's booleanOrNull reads the quoted "false" as false.
                    val primitive = value as? JsonPrimitive
                    val flag = primitive?.booleanOrNull?.takeIf { !primitive.isString }
                        ?: throw UnsupportedSchemaException(
                            "$name: \"additionalProperties\" is not a boolean"
                        )
                    additionalProperties = flag
                }
                "minimum" -> minimum = number(name, key, value)
                "maximum" -> maximum = number(name, key, value)
                "minLength" -> minLength = count(name, key, value)
                "maxLength" -> maxLength = count(name, key, value)
                "minItems" -> minItems = count(name, key, value)
                "maxItems" -> maxItems = count(name, key, value)
                "properties" -> {
                    if (value !is JsonObject) {
                        throw UnsupportedSchemaException("$name: \"properties\" is not an object")
                    }
                    properties = value.mapValues { (property, sub) ->
                        if (sub !is JsonObject) {
                            throw UnsupportedSchemaException(
                                "$name: property '$property' is not a schema object"
                            )
                        }
                        schema(name, sub)
                    }
                }
                "items" -> {
                    if (value !is JsonObject) {
                        throw UnsupportedSchemaException("$name: \"items\" is not a schema object")
                    }
                    items = schema(name, value)
                }
                else -> throw UnsupportedSchemaException("$name: unsupported keyword '$key'")
            }
        }

        return CoachSchema(
            name = name,
            type = type,
            constValue = constValue,
            enumValues = enumValues,
            required = required,
            additionalProperties = additionalProperties,
            minimum = minimum,
            maximum = maximum,
            minLength = minLength,
            maxLength = maxLength,
            minItems = minItems,
            maxItems = maxItems,
            properties = properties,
            items = items,
        )
    }

    private fun number(name: String, key: String, value: JsonElement): JsonPrimitive {
        val primitive = value as? JsonPrimitive
        if (primitive == null || primitive.isString || primitive.booleanOrNull != null) {
            throw UnsupportedSchemaException("$name: \"$key\" is not a number")
        }
        if (primitive.contentOrNull?.toDoubleOrNull() == null) {
            throw UnsupportedSchemaException("$name: \"$key\" is not a number")
        }
        return primitive
    }

    private fun count(name: String, key: String, value: JsonElement): Int {
        val primitive = value as? JsonPrimitive
        val whole = primitive?.takeIf { !it.isString }?.contentOrNull?.toIntOrNull()
        if (whole == null || whole < 0) {
            throw UnsupportedSchemaException("$name: \"$key\" is not a whole number")
        }
        return whole
    }
}
