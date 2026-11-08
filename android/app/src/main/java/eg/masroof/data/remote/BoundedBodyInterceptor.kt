package eg.masroof.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException

/** A response body larger than the cap: treated as data that broke the contract, never buffered further. */
class ResponseTooLargeException : IOException("Response body too large")

/**
 * Network interceptor that stops a response body at [maxBytes] (REQ-SEC-065, hostile server): a declared length above the cap fails
 * at once, and a chunked body fails as soon as more than [maxBytes] have been read. Nothing is logged.
 */
class BoundedBodyInterceptor(
    private val maxBytes: Long,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = response.body
        if (body.contentLength() > maxBytes) {
            response.close()
            throw ResponseTooLargeException()
        }
        val bounded =
            object : ForwardingSource(body.source()) {
                private var read = 0L

                override fun read(
                    sink: Buffer,
                    byteCount: Long,
                ): Long {
                    val n = super.read(sink, byteCount)
                    if (n > 0) read += n
                    if (read > maxBytes) throw ResponseTooLargeException()
                    return n
                }
            }.buffer()
        return response.newBuilder().body(bounded.asResponseBody(body.contentType(), body.contentLength())).build()
    }
}
