package com.alo.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AloOfflineAccountPolicyTest {
    @Test fun selectedAccountNeedsItsOwnInstalledSession() {
        assertEquals(42, AloOfflineAccountPolicy.ownerForSession(42, "alo_session=account-b", "alo_session=account-b"))
        assertEquals(0, AloOfflineAccountPolicy.ownerForSession(42, "alo_session=account-b", "alo_session=account-a"))
        assertEquals(0, AloOfflineAccountPolicy.ownerForSession(42, null, "alo_session=account-b"))
        assertEquals(0, AloOfflineAccountPolicy.ownerForSession(0, "alo_session=account-b", "alo_session=account-b"))
    }

    @Test fun newLoginCannotRestoreThePreviouslySelectedAccountsCache() {
        // B just authenticated, but its bootstrap/session has not been saved yet.
        assertEquals(0, AloOfflineAccountPolicy.ownerForSession(41, "alo_session=old-a", "alo_session=new-b"))
        assertNull(AloOfflineAccountPolicy.bootstrapForOwner(snapshot(41), 42))
    }

    @Test fun cookieOrderingDoesNotBreakOfflineAccessAndValuesRemainExact() {
        assertEquals(42, AloOfflineAccountPolicy.ownerForSession(42,
            "custom_auth=token==; preference=dark", " preference=dark; custom_auth=token== "))
        assertEquals(0, AloOfflineAccountPolicy.ownerForSession(42,
            "custom_auth=token==; preference=dark", "custom_auth=other; preference=dark"))
    }

    @Test fun emptyIncompleteAndAmbiguousCookieHeadersFailClosed() {
        for (header in listOf("", " ", "alo_session=", "not-a-cookie", "alo_session=a; alo_session=b")) {
            assertEquals(header, 0, AloOfflineAccountPolicy.ownerForSession(42, header, header))
        }
        assertEquals(0, AloOfflineAccountPolicy.ownerForSession(42, "alo_session=a; preference=dark", "preference=dark"))
    }

    @Test fun aSnapshotMustBelongToTheExplicitlyRequestedOwner() {
        val a = snapshot(41)
        val b = snapshot(42)
        assertNull(AloOfflineAccountPolicy.bootstrapForOwner(a, 42))
        assertNull(AloOfflineAccountPolicy.bootstrapForOwner(b, 41))
        assertSame(a, AloOfflineAccountPolicy.bootstrapForOwner(a, 41))
        assertSame(b, AloOfflineAccountPolicy.bootstrapForOwner(b, 42))
        assertNull(AloOfflineAccountPolicy.bootstrapForOwner(JSONObject(), 42))
        assertNull(AloOfflineAccountPolicy.bootstrapForOwner(snapshot(0), 0))
    }

    private fun snapshot(owner: Int) = JSONObject().put("data", JSONObject().put("me", JSONObject().put("id", owner)))
}
