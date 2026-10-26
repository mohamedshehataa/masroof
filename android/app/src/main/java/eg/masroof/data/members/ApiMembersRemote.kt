package eg.masroof.data.members

import eg.masroof.api.models.InviteRequest
import eg.masroof.data.remote.ApiRemoteSource
import retrofit2.Response
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_FORBIDDEN = 403
private const val HTTP_CONFLICT = 409
private const val HTTP_UNPROCESSABLE = 422

/** [MembersRemote] over the generated `AuthApi` / `MembersApi` on the authenticated client. */
@Singleton
class ApiMembersRemote
    @Inject
    constructor(
        private val source: ApiRemoteSource,
    ) : MembersRemote {
        private fun <T> Response<T>.bodyOrThrow(): T {
            if (!isSuccessful) throw MembersException(failureOf(code()))
            return body() ?: throw MembersException(MembersFailure.Other)
        }

        private fun failureOf(code: Int): MembersFailure =
            when (code) {
                HTTP_FORBIDDEN -> MembersFailure.Forbidden
                HTTP_CONFLICT -> MembersFailure.Conflict
                HTTP_BAD_REQUEST, HTTP_UNPROCESSABLE -> MembersFailure.Invalid
                else -> MembersFailure.Other
            }

        @Suppress("TooGenericExceptionCaught")
        private suspend fun <T> call(block: suspend () -> T): T =
            try {
                block()
            } catch (e: MembersException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                throw MembersException(MembersFailure.Offline)
            } catch (_: Exception) {
                throw MembersException(MembersFailure.Other)
            }

        override suspend fun members(): List<MemberInfo> =
            call {
                source
                    .membersApi()
                    .listMembers()
                    .bodyOrThrow()
                    .map { MemberInfo(it.id.toString(), it.displayName, it.role.value == "ADMIN") }
            }

        override suspend fun invites(): List<InviteInfo> =
            call {
                source
                    .authApi()
                    .listInvites()
                    .bodyOrThrow()
                    .map { InviteInfo(it.id.toString(), it.displayName, it.expiresAt.toEpochMilliseconds()) }
            }

        override suspend fun createInvite(
            displayName: String,
            email: String?,
        ): CreatedInvite =
            call {
                val created = source.authApi().createInvite(InviteRequest(displayName = displayName, email = email)).bodyOrThrow()
                CreatedInvite(created.id.toString(), created.displayName, created.code)
            }

        override suspend fun revokeInvite(id: String) {
            call {
                val response = source.authApi().revokeInvite(UUID.fromString(id))
                if (!response.isSuccessful) throw MembersException(failureOf(response.code()))
            }
        }
    }
