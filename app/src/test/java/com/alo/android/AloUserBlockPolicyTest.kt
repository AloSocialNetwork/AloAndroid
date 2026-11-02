package com.alo.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AloUserBlockPolicyTest {
    @Test
    fun directCommunicationIsBlockedOnlyForBlockedExternalPeers() {
        val blockedUserIds = setOf(42)

        assertTrue(
            AloUserBlockPolicy.blocksDirectCommunication(
                kind = "direct",
                peerId = 42,
                entityType = "user",
                blockedUserIds = blockedUserIds
            )
        )
        assertFalse(
            AloUserBlockPolicy.blocksDirectCommunication(
                kind = "group",
                peerId = 42,
                entityType = "group",
                blockedUserIds = blockedUserIds
            )
        )
        assertFalse(
            AloUserBlockPolicy.blocksDirectCommunication(
                kind = "direct",
                peerId = 42,
                entityType = "saved",
                blockedUserIds = blockedUserIds
            )
        )
    }

    @Test
    fun updatingBlockStateAddsAndRemovesOnlyValidIds() {
        val blocked = AloUserBlockPolicy.updated(setOf(3), userId = 9, blocked = true)

        assertEquals(setOf(3, 9), blocked)
        assertEquals(setOf(3), AloUserBlockPolicy.updated(blocked, userId = 9, blocked = false))
        assertEquals(setOf(3), AloUserBlockPolicy.updated(setOf(3), userId = 0, blocked = true))
    }

    @Test
    fun parsesSupportedBlockListShapesWithoutInvalidIds() {
        val response = JSONObject()
            .put(
                "blocks",
                JSONArray()
                    .put(JSONObject().put("blockedUserId", 11))
                    .put(JSONObject().put("blockedUser", JSONObject().put("id", 12)))
                    .put(JSONObject().put("user", JSONObject().put("id", 13)))
                    .put(14)
                    .put(JSONObject().put("blockedId", 0))
            )
            .put(
                "data",
                JSONObject().put("blockedUserIds", JSONArray().put(15).put(-1))
            )

        val userIds = AloApiClient("https://api.example.test/").parseBlockedUserIds(response)

        assertEquals(setOf(11, 12, 13, 14, 15), userIds)
    }
}
