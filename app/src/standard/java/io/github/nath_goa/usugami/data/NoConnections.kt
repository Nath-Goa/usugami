package io.github.nath_goa.usugami.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler

/**
 * Keeps Usugami's own process from contacting any server, in the standard edition: the only one
 * with Google ML Kit and the internet permission, which ML Kit's translation needs so that
 * Android's download manager (another process) can download a language's pack.
 *
 * Android creates this provider when the process starts, before the app's other parts and before
 * ML Kit's own provider (initOrder 99). It makes every http, https and ftp URL fail to open, to any
 * host (the phone itself too), before a name is looked up or a connection made. The only code in
 * the app that opens such URLs is ML Kit's: its settings fetch from Firebase Remote Config and its
 * usage-metrics uploader (whose backend the manifest removes besides). ML Kit's other way online,
 * registering with Firebase Installations, has no network code at all (see okhttp3/OkHttp.kt).
 * Nothing else in the app opens a socket or looks up a name (docs/TESTING.md says how to check).
 *
 * It doesn't stop what must go on: the packs' downloads (by the download manager, whose progress
 * the engine reads from it), the engine's questions to ConnectivityManager, text recognition (in
 * Google Play services, reached through Binder), and links, which open in the browser. Nothing
 * can undo it: a process can set this factory once only, and Android never changes it (unlike the
 * default ProxySelector, which it replaces at every proxy change).
 */
internal class NoConnectionsProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        // Throws if a factory was set already, which nothing in the app does: better than going on unprotected.
        URL.setURLStreamHandlerFactory { protocol -> DEFAULT_PORTS[protocol.lowercase()]?.let(::RefusingHandler) }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

/** The URL schemes that reach a server, with their ports: those Android opens itself. */
private val DEFAULT_PORTS = mapOf("http" to 80, "https" to 443, "ftp" to 21)

/** Reads URLs as Android does, but opens none of them and never looks up a name. */
private class RefusingHandler(private val port: Int) : URLStreamHandler() {
    override fun getDefaultPort(): Int = port
    override fun openConnection(u: URL?): URLConnection = throw IOException(REFUSED)
    override fun openConnection(u: URL?, p: Proxy?): URLConnection = throw IOException(REFUSED)

    // Java compares two URLs' hosts by their addresses when it can (URL.equals and hashCode); Android
    // doesn't, but this makes sure: no name is looked up here.
    override fun getHostAddress(u: URL?): InetAddress? = null
}

internal const val REFUSED = "Usugami's process makes no connections"
