package com.maintiflow.server

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** Lecture validée d'un corps JSON (équivalent des schémas pydantic du serveur PC). Erreur -> 422. */
class Body(val obj: JsonObject) {
    fun has(k: String) = obj.containsKey(k)
    fun isNull(k: String) = obj[k] == null || obj[k] is JsonNull

    private fun prim(k: String): JsonPrimitive? {
        val e = obj[k] ?: return null
        if (e is JsonNull) return null
        return e as? JsonPrimitive ?: throw ApiException(422, "Champ $k : valeur simple attendue")
    }

    fun str(k: String, min: Int = 0, max: Int = Int.MAX_VALUE): String? {
        val p = prim(k) ?: return null
        if (!p.isString) throw ApiException(422, "Champ $k : texte attendu")
        val v = p.content
        if (v.length < min) throw ApiException(422, "Champ $k : au moins $min caractères")
        if (v.length > max) throw ApiException(422, "Champ $k : $max caractères maximum")
        return v
    }

    fun reqStr(k: String, min: Int = 1, max: Int = Int.MAX_VALUE): String =
        str(k, min, max) ?: throw ApiException(422, "Champ $k obligatoire")

    fun long(k: String): Long? {
        val p = prim(k) ?: return null
        return p.longOrNull?.takeIf { !p.isString } ?: throw ApiException(422, "Champ $k : nombre entier attendu")
    }

    fun reqLong(k: String): Long = long(k) ?: throw ApiException(422, "Champ $k obligatoire")

    fun bool(k: String): Boolean? {
        val p = prim(k) ?: return null
        return p.booleanOrNull?.takeIf { !p.isString } ?: throw ApiException(422, "Champ $k : vrai/faux attendu")
    }

    fun decimal(k: String): BigDecimal? {
        val p = prim(k) ?: return null
        return p.contentOrNull?.toBigDecimalOrNull() ?: throw ApiException(422, "Champ $k : nombre attendu")
    }

    fun enum(k: String, allowed: Collection<String>): String? {
        val v = str(k) ?: return null
        if (v !in allowed) throw ApiException(422, "Champ $k : valeur invalide ($v)")
        return v
    }

    /** Date ISO-8601 avec fuseau horaire obligatoire. */
    fun instant(k: String): Instant? {
        val v = str(k) ?: return null
        return parseInstant(v) ?: throw ApiException(422, "$k doit contenir une date avec fuseau horaire")
    }

    companion object {
        fun parseInstant(v: String): Instant? = try {
            OffsetDateTime.parse(v).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
