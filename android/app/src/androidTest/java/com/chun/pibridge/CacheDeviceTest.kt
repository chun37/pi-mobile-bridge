package com.chun.pibridge

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CacheDeviceTest {
    @Test
    fun multiMegabyteCacheLoadsWithoutCursorWindowOverflow() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, CacheDb::class.java).build()
        try {
            val longJson = "{\"text\":\"" + "あ🙂".repeat(700000) + "\"}"
            db.cache().put(CacheRow("test", "large", longJson))
            db.cache().put(CacheRow("test", "small", "[]"))
            db.cache().put(CacheRow("other", "private", "not this profile"))
            val loaded = db.cache().all("test").associate { it.key to it.json }
            assertEquals(2, loaded.size)
            assertEquals(longJson, loaded["large"])
            assertEquals("[]", loaded["small"])
        } finally { db.close() }
    }

    @Test
    fun sessionIndexCanBeLoadedWithoutReadingOtherConversations() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, CacheDb::class.java).build()
        try {
            db.cache().put(CacheRow("test", "large", "x".repeat(4 * 1024 * 1024)))
            db.cache().put(CacheRow("test", "sessions", "[]"))
            assertEquals("[]", db.cache().row("test", "sessions")?.json)
            assertNull(db.cache().row("test", "missing"))
        } finally { db.close() }
    }

    @Test
    fun emptyCacheAndEmptyStringArePreserved() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, CacheDb::class.java).build()
        try {
            assertTrue(db.cache().all("test").isEmpty())
            db.cache().put(CacheRow("test", "empty", ""))
            assertEquals("", db.cache().all("test").single().json)
        } finally { db.close() }
    }
}
