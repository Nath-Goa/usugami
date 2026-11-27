package io.github.nath_goa.usugami

import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nath_goa.usugami.data.REFUSED
import io.github.nath_goa.usugami.data.translationEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL

/**
 * Usugami's own process contacts no server (standard edition; NoConnections.kt, okhttp3/OkHttp.kt).
 * URL connections fail at once, before a name is looked up, to any host: also to a server of the
 * test's own on the phone's loopback address, which a plain socket reaches (nothing in the app
 * opens plain sockets, so they aren't blocked), and also with a proxy set for the phone. ML Kit's
 * own request to Firebase is refused the same way. Nothing here reaches the internet, even if the
 * block were gone: opening a URL connection doesn't connect yet.
 */
class NoConnectionsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).apply { soTimeout = 300 }
    private val local = "http://127.0.0.1:${server.localPort}/"
    private val urls = listOf(
        "https://firebaseremoteconfig.googleapis.com/v1/projects/0/namespaces/firebase:fetch",
        "https://firebaseinstallations.googleapis.com/v1/projects/0/installations",
        "ftp://127.0.0.1:${server.localPort}/",
        local,
    )

    @After fun closeServer() = server.close()

    @Test fun urlConnectionsFailAtOnceWithoutReachingAnyServer() {
        Socket().use { it.connect(InetSocketAddress(server.inetAddress, server.localPort), 1000) }
        server.accept().close() // the server is there
        for (url in urls) refusedAtOnce(url)
        nothingReachedTheServer()
    }

    /**
     * Opening a translator makes ML Kit register with Firebase Installations through OkHttp, which
     * here is the stand-in: this fails if ML Kit calls anything the stand-in lacks, as it does
     * before it sends. Without a registration ML Kit never fetches its settings, and keeps none.
     */
    @Test fun mlKitsRequestToFirebaseIsRefused() {
        translationEngine(context)!!.open("fr", "en").use {
            waitFor("ML Kit's request to be refused") {
                shell("logcat -d --pid=${Process.myPid()} -s MLKitFbInstsRestClient").let { log ->
                    "firebaseinstallations.googleapis.com" in log && "IOException: $REFUSED" in log
                }
            }
        }
        val log = shell("logcat -d --pid=${Process.myPid()}")
        for (error in listOf("NoSuchMethodError", "NoClassDefFoundError", "AbstractMethodError", "IncompatibleClassChangeError")) {
            assertFalse("$error in the app's log", error in log)
        }
        val installation = File(context.noBackupFilesDir, "com.google.mlkit.InstallationId")
        if (installation.exists()) installation.readText().let { assertFalse("ML Kit registered: $it", "Token" in it) }
        assertFalse("ML Kit has settings from Firebase", File(context.noBackupFilesDir, "com.google.mlkit.RemoteConfig").exists())
    }

    @Test fun aProxySetForThePhoneChangesNothing() {
        shell("settings put global http_proxy 127.0.0.1:${server.localPort}")
        try {
            // Android tells every process of a new proxy: unblocked, these would now go to the server.
            waitFor("the proxy to reach the app") { System.getProperty("http.proxyHost") == "127.0.0.1" }
            val proxy = ProxySelector.getDefault().select(URI(urls[0])).single()
            assertEquals(Proxy.Type.HTTP, proxy.type())
            assertEquals(server.localPort, (proxy.address() as InetSocketAddress).port)
            for (url in urls) refusedAtOnce(url)
            nothingReachedTheServer()
        } finally {
            shell("settings put global http_proxy :0")
            waitFor("the proxy to be gone") { System.getProperty("http.proxyHost") == null }
        }
    }

    private fun refusedAtOnce(url: String) {
        val started = SystemClock.elapsedRealtime()
        try {
            URL(url).openConnection()
            fail("$url was opened")
        } catch (e: IOException) {
            assertEquals("$url: $e", REFUSED, e.message)
        }
        val took = SystemClock.elapsedRealtime() - started
        assertTrue("$url took $took ms to fail", took < 1000)
    }

    private fun nothingReachedTheServer() {
        try {
            server.accept().close()
            fail("Something connected to the server")
        } catch (_: SocketTimeoutException) {
        }
    }

    private fun shell(command: String): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        return ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    }

    private fun waitFor(what: String, done: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 10_000
        while (!done()) {
            if (SystemClock.elapsedRealtime() > until) fail("Waited 10 s for $what")
            SystemClock.sleep(100)
        }
    }
}
