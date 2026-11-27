@file:Suppress("unused", "UNUSED_PARAMETER")

package okhttp3

import io.github.nath_goa.usugami.data.REFUSED
import java.io.IOException
import java.util.concurrent.TimeUnit

// A stand-in for the HTTP library OkHttp, which Google ML Kit's translation library brings only to
// register an installation of ML Kit with Google's Firebase, and which nothing else in Usugami uses.
// app/build.gradle.kts leaves the real OkHttp out; this has exactly the classes and methods ML Kit
// calls, and every request fails at once, before any name is looked up or any connection is made.
// ML Kit takes that as it takes being offline: it registers nothing, so it never fetches its
// settings from Firebase either (it only does once registered), and translating is not affected.
// (It tries seven times in all, on a thread of its own: twice at once, then five times over the
// next 15 to 45 minutes; each try fails the same way.)
// (Its settings fetch, which uses Android's HttpURLConnection, is refused too: see NoConnections.)
//
// Nothing here may connect: Usugami's process contacts no server. If a newer ML Kit calls anything
// of OkHttp that this lacks, opening a translator crashes the app, and NoConnectionsDeviceTest fails
// (docs/TESTING.md lists what to check after an update). Then add what it needs here, failing the
// same way.

class OkHttpClient private constructor() {
    fun newCall(request: Request?): Call = RefusedCall

    class Builder {
        fun connectTimeout(timeout: Long, unit: TimeUnit?): Builder = this
        fun readTimeout(timeout: Long, unit: TimeUnit?): Builder = this
        fun writeTimeout(timeout: Long, unit: TimeUnit?): Builder = this
        fun build(): OkHttpClient = OkHttpClient()
    }
}

interface Call {
    @Throws(IOException::class)
    fun execute(): Response
}

private object RefusedCall : Call {
    override fun execute(): Response = throw IOException(REFUSED)
}

class Request private constructor() {
    class Builder {
        fun headers(headers: Headers?): Builder = this
        fun url(url: String?): Builder = this
        fun post(body: RequestBody?): Builder = this
        fun build(): Request = Request()
    }
}

class Headers private constructor() {
    class Builder {
        fun add(name: String?, value: String?): Builder = this
        fun build(): Headers = Headers()
    }
}

class MediaType private constructor() {
    companion object {
        @JvmStatic
        fun parse(string: String?): MediaType = MediaType()
    }
}

class RequestBody private constructor() {
    companion object {
        @JvmStatic
        fun create(contentType: MediaType?, content: String?): RequestBody = RequestBody()
    }
}

/** Never made: every [Call] fails first. */
class Response private constructor() {
    fun code(): Int = throw IllegalStateException()
    fun body(): ResponseBody = throw IllegalStateException()
}

/** Never made: every [Call] fails first. */
class ResponseBody private constructor() {
    fun string(): String = throw IllegalStateException()
    fun close() = Unit
}
