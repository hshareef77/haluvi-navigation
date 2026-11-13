package mv.haluvi.navigation.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

class HttpException(val code: Int, val body: String, message: String) : IOException(message)

/** Small blocking HTTP helper. Always call from a background dispatcher. */
object Http {
    private const val USER_AGENT = "HaluviNavigation/2.0 (Android accessible navigation app)"

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun get(url: String, headers: Map<String, String> = emptyMap(), connectMs: Int = 10000, readMs: Int = 20000): String =
        request("GET", url, null, headers, connectMs, readMs)

    fun post(url: String, body: String, contentType: String, headers: Map<String, String> = emptyMap(), connectMs: Int = 10000, readMs: Int = 30000): String =
        request("POST", url, body, headers + ("Content-Type" to contentType), connectMs, readMs)

    private fun request(method: String, url: String, body: String?, headers: Map<String, String>, connectMs: Int, readMs: Int): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectMs
            conn.readTimeout = readMs
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Accept-Language", "en")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpException(code, text, "Server response $code")
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** Short, speakable error message (no raw Android exception text). */
    fun friendly(e: Throwable, service: String = "Online service"): String = when (e) {
        is UnknownHostException -> "Internet connection is not ready. Please check internet and try again."
        is SocketTimeoutException -> "$service is not responding. Please try again."
        is HttpException -> when (e.code) {
            429 -> "$service is busy. Please wait a little and try again."
            403 -> "$service refused the request. Check the API key, enabled APIs and billing."
            in 500..599 -> "$service is temporarily unavailable. Please try again."
            else -> "$service error ${e.code}."
        }
        else -> e.message?.takeIf { it.length < 160 && !it.contains("Exception") } ?: "$service request failed."
    }
}
