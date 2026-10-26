package eg.masroof.data.members

/** A household member as the Settings > Members section shows it. */
data class MemberInfo(
    val id: String,
    val displayName: String,
    val isAdmin: Boolean,
)

/** A pending invite. The code is never part of it: it is shown once, when the invite is created. */
data class InviteInfo(
    val id: String,
    val displayName: String,
    val expiresAtMillis: Long,
)

/** The one-time result of creating an invite. [toString] is redacted so a stray log line can never leak the code. */
data class CreatedInvite(
    val id: String,
    val displayName: String,
    val code: String,
) {
    override fun toString(): String = "CreatedInvite(id=$id)"
}

/** Why a members call failed, as the UI needs to tell them apart. */
sealed interface MembersFailure {
    /** 403: the caller is a plain member. */
    data object Forbidden : MembersFailure

    /** 409: too many pending invites or the household is full. */
    data object Conflict : MembersFailure

    /** 400/422: the name or e-mail was rejected. */
    data object Invalid : MembersFailure

    data object Offline : MembersFailure

    data object Other : MembersFailure
}

class MembersException(
    val failure: MembersFailure,
) : Exception(failure.toString())

interface MembersRemote {
    suspend fun members(): List<MemberInfo>

    /** @throws MembersException with [MembersFailure.Forbidden] for a non-admin. */
    suspend fun invites(): List<InviteInfo>

    suspend fun createInvite(
        displayName: String,
        email: String?,
    ): CreatedInvite

    suspend fun revokeInvite(id: String)
}

/** The shareable link; the code sits in the URL fragment so it never reaches a server log. */
fun inviteLink(
    serverBase: String,
    code: String,
): String = "${serverBase.trimEnd('/')}/invite#$code"
