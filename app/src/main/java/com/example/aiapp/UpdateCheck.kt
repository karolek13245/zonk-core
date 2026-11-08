package com.example.aiapp

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One published version of the app, as listed on the project's GitHub Releases page. */
data class ReleaseInfo(
    /** The tag, for example "V1.3.2". */
    val tag: String,
    val title: String,
    /** The release notes as plain text. */
    val notes: String,
    /** The release page. Always an address inside the project's own GitHub repo. */
    val url: String,
    /** The day it was published ("2026-11-05"), or "" if unknown. */
    val date: String,
)

/** What the update pop-up shows. */
sealed interface UpdateUi {
    /** A newer version exists. [newer] holds every newer release, newest first. */
    class Available(val newer: List<ReleaseInfo>) : UpdateUi

    /** This is the newest version. [recent] holds the latest few releases (the "what's new" list). */
    class UpToDate(val recent: List<ReleaseInfo>) : UpdateUi

    /** The check did not work (no internet, GitHub busy, ...). */
    object Failed : UpdateUi
}

private const val RELEASES_API = "https://api.github.com/repos/karolek13245/zonk-core/releases?per_page=10"
private const val RELEASE_PAGE_PREFIX = "$REPO_URL/releases/"
private const val MAX_RESPONSE_BYTES = 512 * 1024
private const val MAX_NOTES_CHARS = 1500

// ---------- Version numbers ----------

private val TAG_RE = Regex("^[vV]?(\\d{1,4}(?:\\.\\d{1,4}){0,3})[vV]?$")
private val DATE_RE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

/**
 * "V1.3.2" becomes [1, 3, 2] and "1.1v" becomes [1, 1] (the project's older tags put the v at the end).
 * Anything that is not a plain version number ("nightly", "1.4-beta") gives null and is ignored.
 */
fun versionParts(tag: String): List<Int>? {
    val m = TAG_RE.matchEntire(tag.trim()) ?: return null
    return m.groupValues[1].split('.').map { it.toInt() }
}

/** Positive if [a] is newer than [b], negative if older, 0 if the same. A missing part counts as 0 ("1.3" equals "1.3.0"). */
fun compareVersions(a: List<Int>, b: List<Int>): Int {
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x.compareTo(y)
    }
    return 0
}

/** True if [tag] is a newer version than [installed]. Anything that isn't a version number is never newer. */
fun isNewerVersion(tag: String, installed: String): Boolean {
    val t = versionParts(tag) ?: return false
    val i = versionParts(installed) ?: return false
    return compareVersions(t, i) > 0
}

/**
 * How a version is shown to people. The fourth number is the build: "1.3.2.2" reads "1.3.2 (build 2)".
 * Anything else ("1.3.2", "V1.4") is shown as it is.
 */
fun versionLabel(version: String): String {
    val p = versionParts(version)
    return if (p != null && p.size == 4) "${p[0]}.${p[1]}.${p[2]} (build ${p[3]})" else version
}

// ---------- Release notes ----------

private val HEADING_RE = Regex("^\\s{0,3}#{1,6}\\s*")
private val BULLET_RE = Regex("^(\\s*)[-*+]\\s+")
private val LINK_RE = Regex("!?\\[([^\\]]*)]\\([^)]*\\)")
private val BLANKS_RE = Regex("\\n{3,}")

private fun cutText(s: String, max: Int): String {
    if (s.length <= max) return s
    var end = max
    if (end > 0 && Character.isHighSurrogate(s[end - 1])) end--   // never cut an emoji in half
    return s.substring(0, end).trimEnd() + "…"
}

/**
 * Release notes are often written in Markdown. The pop-up shows plain text, so this removes the markup
 * (headings, bold, code marks, link addresses), turns "- " bullets into "• ", and shortens very long notes.
 */
fun plainNotes(markdown: String, maxChars: Int = MAX_NOTES_CHARS): String {
    val text = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n').joinToString("\n") { line ->
        line.trimEnd()
            .replace(HEADING_RE, "")
            .replace(BULLET_RE, "\$1• ")
            .replace(LINK_RE, "\$1")
            .replace("**", "")
            .replace("__", "")
            .replace("`", "")
    }.replace(BLANKS_RE, "\n\n").trim()
    return cutText(text, maxChars)
}

// ---------- Reading GitHub's answer ----------

/** A text field of a JSON object. A missing field or a JSON null gives "" (Android would turn null into the word "null"). */
private fun JSONObject.text(name: String): String = if (isNull(name)) "" else optString(name, "")

/**
 * Turns GitHub's list of releases into [ReleaseInfo]s, newest version first. Drafts, pre-releases and tags that
 * are not version numbers are left out. Throws if [json] is not a JSON list.
 */
fun parseReleases(json: String): List<ReleaseInfo> {
    val arr = JSONArray(json)
    val found = ArrayList<ReleaseInfo>()
    for (i in 0 until minOf(arr.length(), 30)) {
        val o = arr.optJSONObject(i) ?: continue
        if (o.optBoolean("draft", false) || o.optBoolean("prerelease", false)) continue
        val tag = o.text("tag_name").trim()
        if (versionParts(tag) == null) continue
        val page = o.text("html_url")
        found.add(
            ReleaseInfo(
                tag = tag,
                title = o.text("name").trim().take(80),
                notes = plainNotes(o.text("body")),
                // Only ever link to this project's own release pages, whatever the answer says.
                url = if (page.startsWith(RELEASE_PAGE_PREFIX)) page
                else RELEASE_PAGE_PREFIX + "tag/" + URLEncoder.encode(tag, "UTF-8"),
                date = o.text("published_at").take(10).takeIf { DATE_RE.matches(it) } ?: "",
            )
        )
    }
    return found.sortedWith { a, b -> compareVersions(versionParts(b.tag)!!, versionParts(a.tag)!!) }
}

/** Reads at most [maxBytes] bytes as UTF-8 text. Returns null if there is more than that. */
internal fun readLimited(input: InputStream, maxBytes: Int): String? {
    val out = ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    while (true) {
        val n = input.read(chunk)
        if (n < 0) break
        if (out.size() + n > maxBytes) return null
        out.write(chunk, 0, n)
    }
    return String(out.toByteArray(), Charsets.UTF_8)
}

/**
 * Asks GitHub for the newest published versions of Zonk-Core. Blocking: call it off the main thread.
 * Returns null on any problem (no internet, rate limit, an odd answer). Nothing about the person or the
 * phone is sent, only the app's name and version in the User-Agent line that GitHub asks for.
 */
fun fetchReleases(): List<ReleaseInfo>? {
    return try {
        val conn = URL(RELEASES_API).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.setRequestProperty("User-Agent", "Zonk-Core/$APP_VERSION")
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.use { readLimited(it, MAX_RESPONSE_BYTES) } ?: return null
            parseReleases(body)
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        null
    }
}
