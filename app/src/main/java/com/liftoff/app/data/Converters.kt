package com.liftoff.app.data

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.LocalDate

object Converters {

    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun localDateToLong(date: LocalDate?): Long? = date?.toEpochDay()

    @TypeConverter
    fun longToLocalDay(epoch: Long?): LocalDate? = epoch?.let(LocalDate::ofEpochDay)

    @TypeConverter
    fun listStringToJson(list: List<String>?): String? =
        list?.let { json.encodeToString(ListSerializer(String.serializer()), it) }

    @TypeConverter
    fun jsonToListString(jsonStr: String?): List<String>? =
        jsonStr?.let { json.decodeFromString(ListSerializer(String.serializer()), it) }
}
