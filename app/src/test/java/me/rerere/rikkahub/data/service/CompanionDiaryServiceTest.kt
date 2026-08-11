package me.rerere.rikkahub.data.service

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.DiaryCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CompanionDiaryServiceTest {
    @Test
    fun `confirmed diary hold args use Ombre scalar types`() {
        val args = buildConfirmedDiaryHoldArgs(
            DiaryCandidate(title = "2026-08-10 · Daddy 的日记候选", content = "今天一起修好了日记。")
        )

        assertEquals(JsonPrimitive("今天一起修好了日记。"), args["content"])
        assertEquals(JsonPrimitive("2026-08-10 · Daddy 的日记候选"), args["title"])
        assertEquals("diary,user-confirmed", args["tags"]!!.jsonPrimitive.content)
        assertEquals(5, args["importance"]!!.jsonPrimitive.int)
        assertEquals(false, args["pinned"]!!.jsonPrimitive.boolean)
        assertEquals(false, args["feel"]!!.jsonPrimitive.boolean)
        assertEquals(-1.0, args["valence"]!!.jsonPrimitive.double, 0.0)
        assertEquals(-1.0, args["arousal"]!!.jsonPrimitive.double, 0.0)
    }

    @Test
    fun `Ombre hold result fails when mcp marks the call as error`() {
        val failure = describeOmbreHoldFailure(rawResult = "schema validation failed", isError = true)

        assertNotNull(failure)
    }

    @Test
    fun `Ombre hold result accepts normal success text`() {
        val failure = describeOmbreHoldFailure(rawResult = "已保存，bucket_id=abc123", isError = false)

        assertNull(failure)
    }
}
