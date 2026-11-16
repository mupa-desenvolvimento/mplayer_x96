package com.mupa.player.enterprise

import com.mupa.player.enterprise.services.TagsJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagsJsonTest {

    private val json = """
        [
          {"id":"3","name":"Premium","slug":"premium","category":"general","color":"#8b5cf6","is_active":true},
          {"id":"1","name":"loja 1","slug":"loja-1","category":"store","color":"#3B82F6","is_active":true},
          {"id":"2","name":"Litoral","slug":"litoral","category":"general","color":"#3b82f6","is_active":true},
          {"id":"4","name":"Inativa","slug":"inativa","category":"general","is_active":false},
          {"id":"5","slug":"sem-nome","category":"general","is_active":true},
          {"name":"Sem id","category":"general","is_active":true}
        ]
    """.trimIndent()

    @Test
    fun parse_dropsInactiveAndIncompleteRows() {
        val ids = TagsJson.parse(json).map { it.id }
        assertTrue("4" !in ids && "5" !in ids)
        assertEquals(3, ids.size)
    }

    @Test
    fun parse_sortsByCategoryThenNameIgnoringCase() {
        // general (Litoral, Premium) antes de store (loja 1)
        assertEquals(listOf("2", "3", "1"), TagsJson.parse(json).map { it.id })
    }

    @Test
    fun parse_keepsOptionalFields_andNullsMissingOnes() {
        val t = TagsJson.parse("""[{"id":"9","name":"X"}]""").single()
        assertNull(t.slug)
        assertNull(t.category)
        assertNull(t.color)
        assertEquals("X", t.name)
    }

    @Test
    fun parse_garbageOrObject_isEmpty() {
        assertTrue(TagsJson.parse("garbage").isEmpty())
        assertTrue(TagsJson.parse("{}").isEmpty())
        assertTrue(TagsJson.parse("[]").isEmpty())
        assertTrue(TagsJson.parse("").isEmpty())
    }
}
