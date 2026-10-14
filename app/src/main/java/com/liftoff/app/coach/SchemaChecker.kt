package com.liftoff.app.coach

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Where a problem sits in the document, and what is wrong there. A later brief sends this verbatim. */
data class SchemaError(val path: String, val problem: String) {
    override fun toString(): String = "$path: $problem"
}

/**
 * Validates a parsed JSON value against a [CoachSchema], over exactly the 13 keywords the model
 * implements. An empty list means the value is valid.
 *
 * Order at every node — own keywords, then `required` in schema order, then unexpected properties in
 * document order, then declared `properties` in schema order, then array `items` by index — is what
 * makes the list stable for the same input. Every violation is reported in one pass.
 */
object SchemaChecker {

    /** The path an error about the document itself carries; a child of the root is written bare. */
    const val ROOT: String = "\$"

    fun check(value: JsonElement, schema: CoachSchema): List<SchemaError> {
        val errors = mutableListOf<SchemaError>()
        node(value, schema, ROOT, errors)
        return errors
    }

    private fun node(value: JsonElement, schema: CoachSchema, path: String, errors: MutableList<SchemaError>) {
        // A wrong-typed value gets the type error only: its bounds, keys and items say nothing the
        // type error did not, and a repair prompt reads best with each problem named once.
        if (!checkType(value, schema, path, errors)) return
        checkConstAndEnum(value, schema, path, errors)
        checkBounds(value, schema, path, errors)
        if (value is JsonObject) {
            checkRequired(value, schema, path, errors)
            checkUnexpectedProperties(value, schema, path, errors)
            checkProperties(value, schema, path, errors)
        }
        if (value is JsonArray) {
            checkItems(value, schema, path, errors)
        }
    }

    /** True when the node should be walked further, i.e. it has no type or its type matched. */
    private fun checkType(value: JsonElement, schema: CoachSchema, path: String, errors: MutableList<SchemaError>): Boolean {
        val expected = schema.type ?: return true
        if (matchesType(value, expected)) return true
        errors += SchemaError(path, "expected ${typeWord(expected)}, got ${valueWord(value)}")
        return false
    }

    private fun checkConstAndEnum(value: JsonElement, schema: CoachSchema, path: String, errors: MutableList<SchemaError>) {
        schema.constValue?.let { required ->
            if (value != required) errors += SchemaError(path, "must be ${rendered(required)}")
        }
        val allowed = schema.enumValues ?: return
        if (allowed.none { value == it }) {
            errors += SchemaError(
                path,
                "${rendered(value)} is not one of " + allowed.joinToString(", ") { literal(it) },
            )
        }
    }

    /** Bounds apply only to a value they mean something for: numbers, strings, arrays. */
    private fun checkBounds(value: JsonElement, schema: CoachSchema, path: String, errors: MutableList<SchemaError>) {
        val number = numberValue(value)
        if (number != null) {
            schema.minimum?.let {
                val bound = it.content.toDoubleOrNull()
                if (bound != null && number < bound) errors += SchemaError(path, "must be at least ${it.content}")
            }
            schema.maximum?.let {
                val bound = it.content.toDoubleOrNull()
                if (bound != null && number > bound) errors += SchemaError(path, "must be at most ${it.content}")
            }
        }
        if (value is JsonPrimitive && value.isString) {
            schema.minLength?.let {
                if (value.content.length < it) errors += SchemaError(path, "must be at least ${count(it, "character")}")
            }
            schema.maxLength?.let {
                if (value.content.length > it) errors += SchemaError(path, "must be at most ${count(it, "character")}")
            }
        }
        if (value is JsonArray) {
            schema.minItems?.let {
                if (value.size < it) errors += SchemaError(path, "must have at least ${count(it, "item")}")
            }
            schema.maxItems?.let {
                if (value.size > it) errors += SchemaError(path, "must have at most ${count(it, "item")}")
            }
        }
    }

    private fun checkRequired(value: JsonObject, schema: CoachSchema, path: String, errors: MutableList<SchemaError>) {
        for (name in schema.required) {
            if (!value.containsKey(name)) errors += SchemaError(path, "missing required property '$name'")
        }
    }

    private fun checkUnexpectedProperties(
        value: JsonObject,
        schema: CoachSchema,
        path: String,
        errors: MutableList<SchemaError>,
    ) {
        // An omitted additionalProperties means extra properties are allowed, as in JSON Schema.
        if (schema.additionalProperties != false) return
        for (name in value.keys) {
            if (!schema.properties.containsKey(name)) errors += SchemaError(path, "unexpected property '$name'")
        }
    }

    private fun checkProperties(value: JsonObject, schema: CoachSchema, path: String, errors: MutableList<SchemaError>) {
        for ((name, sub) in schema.properties) {
            val child = value[name] ?: continue
            node(child, sub, if (path == ROOT) name else "$path.$name", errors)
        }
    }

    private fun checkItems(value: JsonArray, schema: CoachSchema, path: String, errors: MutableList<SchemaError>) {
        val sub = schema.items ?: return
        value.forEachIndexed { index, item -> node(item, sub, "$path[$index]", errors) }
    }

    private fun matchesType(value: JsonElement, type: SchemaType): Boolean =
        when (type) {
            SchemaType.OBJECT -> value is JsonObject
            SchemaType.ARRAY -> value is JsonArray
            SchemaType.STRING -> value is JsonPrimitive && value.isString
            // isString first: kotlinx's booleanOrNull reads the quoted "true" as true.
            SchemaType.BOOLEAN -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
            SchemaType.NULL -> value is JsonNull
            SchemaType.NUMBER -> numberValue(value) != null
            // JSON has one number type, so `45.0` means 45 and passes; `45.5` is not an integer.
            SchemaType.INTEGER -> numberValue(value)?.rem(1.0) == 0.0
        }

    /** null unless the value is a JSON number; a quoted number is a string, not a number. */
    private fun numberValue(value: JsonElement): Double? {
        if (value !is JsonPrimitive || value is JsonNull || value.isString) return null
        if (value.booleanOrNull != null) return null
        return value.content.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private fun typeWord(type: SchemaType): String =
        when (type) {
            SchemaType.OBJECT -> "an object"
            SchemaType.ARRAY -> "an array"
            SchemaType.STRING -> "a string"
            SchemaType.INTEGER -> "an integer"
            SchemaType.NUMBER -> "a number"
            SchemaType.BOOLEAN -> "a boolean"
            SchemaType.NULL -> "null"
        }

    /** A JSON number has one type, so an integer mismatch reports the value as the number it is. */
    private fun valueWord(value: JsonElement): String =
        when {
            value is JsonObject -> "an object"
            value is JsonArray -> "an array"
            value is JsonNull -> "null"
            value is JsonPrimitive && value.isString -> "a string"
            value is JsonPrimitive && value.booleanOrNull != null -> "a boolean"
            else -> "a number"
        }

    /** A string in single quotes; anything else as its own compact JSON text. */
    private fun rendered(value: JsonElement): String =
        if (value is JsonPrimitive && value.isString) "'${value.content}'" else value.toString()

    /** The allowed values of an `enum` are listed bare: `easy, tempo, intervals`. */
    private fun literal(value: JsonElement): String =
        if (value is JsonPrimitive && value.isString) value.content else value.toString()

    private fun count(limit: Int, unit: String): String =
        if (limit == 1) "$limit $unit" else "$limit ${unit}s"
}
