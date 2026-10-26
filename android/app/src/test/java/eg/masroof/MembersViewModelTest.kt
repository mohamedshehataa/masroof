package eg.masroof

import eg.masroof.data.AppSettings
import eg.masroof.data.members.CreatedInvite
import eg.masroof.data.members.InviteInfo
import eg.masroof.data.members.MemberInfo
import eg.masroof.data.members.MembersException
import eg.masroof.data.members.MembersFailure
import eg.masroof.data.members.MembersRemote
import eg.masroof.data.members.inviteLink
import eg.masroof.ui.viewmodel.MembersError
import eg.masroof.ui.viewmodel.MembersViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeMembersRemote : MembersRemote {
    var members =
        listOf(MemberInfo("m1", "Mohamed", true), MemberInfo("m2", "Mai", false))
    var invites = mutableListOf<InviteInfo>()
    var me: String? = "m1"
    var membersFailure: MembersFailure? = null
    var createFailure: MembersFailure? = null
    val created = mutableListOf<Pair<String, String?>>()
    val revoked = mutableListOf<String>()

    override suspend fun members(): List<MemberInfo> {
        membersFailure?.let { throw MembersException(it) }
        return members
    }

    override suspend fun myMemberId(): String? = me

    override suspend fun invites(): List<InviteInfo> = invites.toList()

    override suspend fun createInvite(
        displayName: String,
        email: String?,
    ): CreatedInvite {
        createFailure?.let { throw MembersException(it) }
        created += displayName to email
        invites += InviteInfo("i${invites.size}", displayName, 1_000L)
        return CreatedInvite("i${invites.size - 1}", displayName, "SECRET-CODE")
    }

    override suspend fun revokeInvite(id: String) {
        revoked += id
        invites.removeAll { it.id == id }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MembersViewModelTest {
    private val remote = FakeMembersRemote()
    private val store = FakeSettings()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `REQ-SEC-060 an admin sees both members and an empty invite list`() =
        runTest {
            val vm = MembersViewModel(remote, store)
            val s = vm.state.value
            assertFalse(s.loading)
            assertEquals(listOf("Mohamed", "Mai"), s.members.map { it.displayName })
            assertTrue(s.isAdmin)
            assertTrue(s.invites.isEmpty())
        }

    @Test
    fun `REQ-SEC-060 a plain member gets the list but no invite controls`() =
        runTest {
            remote.me = "m2"
            val vm = MembersViewModel(remote, store)
            assertEquals(2, vm.state.value.members.size)
            assertFalse(vm.state.value.isAdmin)
        }

    @Test
    fun `REQ-SEC-060 creating an invite builds a fragment link from the server url and clears the form`() =
        runTest {
            store.update { it.copy(serverUrl = "https://masroof.example") }
            val vm = MembersViewModel(remote, store)
            vm.onName("  Mai ")
            vm.onEmail(" mai@example.com ")
            vm.create()
            val s = vm.state.value
            assertEquals(listOf("Mai" to "mai@example.com"), remote.created)
            assertEquals("https://masroof.example/invite#SECRET-CODE", s.link)
            assertFalse(s.link!!.contains("?"))
            assertEquals("", s.name)
            assertEquals(1, s.invites.size)
            assertFalse(s.created.toString().contains("SECRET-CODE"))
        }

    @Test
    fun `REQ-SEC-060 dismissing the created panel forgets the code and link`() =
        runTest {
            val vm = MembersViewModel(remote, store)
            vm.onName("Mai")
            vm.create()
            vm.dismissCreated()
            assertNull(vm.state.value.created)
            assertNull(vm.state.value.link)
        }

    @Test
    fun `REQ-SEC-060 a blank name creates nothing`() =
        runTest {
            val vm = MembersViewModel(remote, store)
            vm.onName("   ")
            vm.create()
            assertTrue(remote.created.isEmpty())
        }

    @Test
    fun `REQ-SEC-060 server refusals map to errors the form can show`() =
        runTest {
            val vm = MembersViewModel(remote, store)
            vm.onName("Mai")
            remote.createFailure = MembersFailure.Conflict
            vm.create()
            assertEquals(MembersError.CONFLICT, vm.state.value.error)
            remote.createFailure = MembersFailure.Offline
            vm.create()
            assertEquals(MembersError.OFFLINE, vm.state.value.error)
            assertNull(vm.state.value.created)
            vm.onName("Mai2") // editing clears the error
            assertNull(vm.state.value.error)
        }

    @Test
    fun `REQ-SEC-060 revoking removes the invite from the list`() =
        runTest {
            remote.invites += InviteInfo("i9", "Sara", 1_000L)
            val vm = MembersViewModel(remote, store)
            assertEquals(1, vm.state.value.invites.size)
            vm.revoke("i9")
            assertEquals(listOf("i9"), remote.revoked)
            assertTrue(
                vm.state.value.invites
                    .isEmpty(),
            )
        }

    @Test
    fun `REQ-SEC-060 a failed load offers retry and a retry recovers`() =
        runTest {
            remote.membersFailure = MembersFailure.Offline
            val vm = MembersViewModel(remote, store)
            assertTrue(vm.state.value.loadFailed)
            remote.membersFailure = null
            vm.refresh()
            assertFalse(vm.state.value.loadFailed)
            assertEquals(2, vm.state.value.members.size)
        }

    @Test
    fun `REQ-SEC-060 the link keeps the code in the fragment`() {
        assertEquals("https://h.example/invite#abc", inviteLink("https://h.example/", "abc"))
        assertEquals(AppSettings.DEFAULT_SERVER_URL.trimEnd('/') + "/invite#x", inviteLink(AppSettings.DEFAULT_SERVER_URL, "x"))
    }

    @Test
    fun `REQ-SEC-060 admin comes from the member role even when listing invites would fail`() =
        runTest {
            remote.me = "m1"
            val vm = MembersViewModel(remote, store)
            assertTrue(vm.state.value.isAdmin)
            remote.me = null
            vm.refresh()
            assertFalse(vm.state.value.isAdmin)
            assertTrue(
                vm.state.value.invites
                    .isEmpty(),
            )
        }
}
