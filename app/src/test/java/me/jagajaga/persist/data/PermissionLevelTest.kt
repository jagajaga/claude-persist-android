package me.jagajaga.persist.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionLevelTest {
    private fun s(mode: String, auto: Boolean = false) = SessionInfo(id = "s", title = "t", cwd = "/", permissionMode = mode, autoApprove = auto)

    @Test
    fun `a chat's level reads from its mode and super bypass`() {
        assertEquals(PermissionLevel.Ask, PermissionLevel.of(s("default")))
        assertEquals(PermissionLevel.Bypass, PermissionLevel.of(s("bypassPermissions")))
        assertEquals(PermissionLevel.SuperBypass, PermissionLevel.of(s("bypassPermissions", auto = true)))
        // Super bypass only ever means "bypass, and more": without bypass it is not on.
        assertEquals(PermissionLevel.Ask, PermissionLevel.of(s("default", auto = true)))
    }

    @Test
    fun `the shield moves ask, bypass, super bypass, and back to ask`() {
        assertEquals(PermissionLevel.Bypass, PermissionLevel.Ask.next())
        assertEquals(PermissionLevel.SuperBypass, PermissionLevel.Bypass.next())
        assertEquals(PermissionLevel.Ask, PermissionLevel.SuperBypass.next())
    }
}
