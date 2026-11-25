package io.wingpane

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Test

class ClientHolderTest {
    @Test fun returnsSameInstanceOnRepeatedAccess() {
        var creations = 0
        val holder = ClientHolder {
            creations++
            Any()
        }
        val first = holder.client
        val second = holder.client
        assertSame(first, second)
        assertEquals(1, creations)
    }

    @Test fun createsFreshClientAfterUnprotectedSessionEnds() {
        val holder = ClientHolder { Any() }
        val first = holder.client
        holder.clear()
        assertNotSame(first, holder.client)
    }
}
