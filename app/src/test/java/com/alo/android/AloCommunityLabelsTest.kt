package com.alo.android

import org.junit.Assert.assertEquals
import org.junit.Test

class AloCommunityLabelsTest {
    @Test
    fun subscriberCountUsesRussianPluralForms() {
        assertEquals("1 подписчик", formatSubscriberCount(1))
        assertEquals("2 подписчика", formatSubscriberCount(2))
        assertEquals("5 подписчиков", formatSubscriberCount(5))
        assertEquals("11 подписчиков", formatSubscriberCount(11))
        assertEquals("21 подписчик", formatSubscriberCount(21))
    }

    @Test
    fun memberAndSubscriberLabelsStayDistinct() {
        assertEquals("3 участника", formatMemberCount(3))
        assertEquals("3 подписчика", formatSubscriberCount(3))
    }
}
