package com.naudio.data.repository

import com.naudio.core.database.entity.TrackEntity
import org.junit.Assert
import org.junit.Test

class ClasspathTest {
    @Test
    fun `test TrackEntity is visible`() {
        Assert.assertTrue(TrackEntity::class.java.canonicalName != null)
    }
}
