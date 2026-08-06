package me.rerere.rikkahub.data.service

import org.junit.Assert.assertEquals
import org.junit.Test

class NightWatchMessageClassifierTest {
    @Test
    fun `bedtime words arm the watch`() {
        assertEquals(NightWatchMessageClassifier.Action.Arm, NightWatchMessageClassifier.classify("宝宝晚安，我去睡啦"))
        assertEquals(NightWatchMessageClassifier.Action.Arm, NightWatchMessageClassifier.classify("我先睡了"))
    }

    @Test
    fun `explicit stop words disarm even when sleep words are present`() {
        assertEquals(NightWatchMessageClassifier.Action.Disarm, NightWatchMessageClassifier.classify("我不睡了，别管我啦"))
        assertEquals(NightWatchMessageClassifier.Action.Disarm, NightWatchMessageClassifier.classify("我起床了"))
    }

    @Test
    fun `negative sleep statements do not accidentally arm`() {
        assertEquals(NightWatchMessageClassifier.Action.None, NightWatchMessageClassifier.classify("我还不想睡"))
        assertEquals(NightWatchMessageClassifier.Action.None, NightWatchMessageClassifier.classify("今晚睡不着"))
    }
}
