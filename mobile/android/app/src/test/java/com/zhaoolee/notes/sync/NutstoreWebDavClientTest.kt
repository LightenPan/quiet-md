package com.zhaoolee.notes.sync

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

class NutstoreWebDavClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(): NutstoreWebDavClient = NutstoreWebDavClient(
        WebDavConfig.create(
            server = server.url("/dav/").toString(),
            username = "me@example.com",
            remoteRoot = "Quiet.md"
        ),
        "app-password"
    )

    @Test
    fun probe_uses_propfind_and_basic_auth_header() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                multistatus("""<d:response><d:href>/dav/Quiet.md/</d:href>
                    <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                    <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>""")
            )
        )

        client().probe()

        val request = server.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("/dav/Quiet.md/", request.path)
        assertEquals("0", request.getHeader("Depth"))
        assertTrue(request.getHeader("Authorization")!!.startsWith("Basic "))
        val credentials = String(
            Base64.getDecoder().decode(request.getHeader("Authorization")!!.removePrefix("Basic ")),
            Charsets.UTF_8
        )
        assertEquals("me@example.com:app-password", credentials)
        assertFalse(request.path!!.contains("app-password"))
        assertFalse(request.getHeader("Authorization")!!.contains("app-password"))
    }

    @Test
    fun put_uses_if_match_for_updates_and_if_none_match_for_creates() = runBlocking {
        val statBody = multistatus("""<d:response><d:href>/dav/Quiet.md/a.md</d:href>
            <d:propstat><d:prop><d:resourcetype/><d:getetag>"new"</d:getetag>
            <d:getcontentlength>1</d:getcontentlength></d:prop></d:propstat></d:response>""")
        repeat(2) {
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(207).setBody(statBody))
        }
        val api = client()

        api.put("a.md", byteArrayOf(1), "\"old\"")
        api.put("a.md", byteArrayOf(1), null)

        val first = server.takeRequest()
        val stat = server.takeRequest()
        val second = server.takeRequest()
        server.takeRequest()
        assertEquals("PUT", first.method)
        assertEquals("\"old\"", first.getHeader("If-Match"))
        assertNull(first.getHeader("If-None-Match"))
        assertEquals("PROPFIND", stat.method)
        assertEquals("0", stat.getHeader("Depth"))
        assertEquals("*", second.getHeader("If-None-Match"))
        assertNull(second.getHeader("If-Match"))
    }

    @Test
    fun scan_treats_a_one_level_infinity_reply_as_incomplete() = runBlocking {
        val top = multistatus("""
            <d:response><d:href>/dav/Quiet.md/</d:href><d:propstat><d:prop>
            <d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
            <d:response><d:href>/dav/Quiet.md/%E7%AC%94%E8%AE%B0/</d:href><d:propstat><d:prop>
            <d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
        """)
        val level = multistatus("""
            <d:response><d:href>/dav/Quiet.md/%E7%AC%94%E8%AE%B0/</d:href><d:propstat><d:prop>
            <d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
            <d:response><d:href>/dav/Quiet.md/%E7%AC%94%E8%AE%B0/%E5%91%A8%E6%8A%A5.md</d:href>
            <d:propstat><d:prop><d:resourcetype/><d:getetag>"e1"</d:getetag>
            <d:getcontentlength>12</d:getcontentlength></d:prop></d:propstat></d:response>
        """)
        repeat(2) { server.enqueue(MockResponse().setResponseCode(207).setBody(top)) }
        server.enqueue(MockResponse().setResponseCode(207).setBody(level))

        val entries = client().scan()

        assertTrue(entries.any { it.rel == "笔记/周报.md" && it.etag == "\"e1\"" })
        assertEquals("infinity", server.takeRequest().getHeader("Depth"))
        assertEquals("1", server.takeRequest().getHeader("Depth"))
        assertEquals("1", server.takeRequest().getHeader("Depth"))
    }

    @Test
    fun scan_falls_back_to_levels_and_decodes_unicode_paths() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                multistatus("""
                    <d:response><d:href>/dav/Quiet.md/</d:href><d:propstat><d:prop>
                    <d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
                    <d:response><d:href>/dav/Quiet.md/Work/</d:href><d:propstat><d:prop>
                    <d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
                """)
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                multistatus("""
                    <d:response><d:href>/dav/Quiet.md/Work/</d:href><d:propstat><d:prop>
                    <d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
                    <d:response><d:href>/dav/Quiet.md/Work/%E5%91%A8%E6%8A%A5.md</d:href>
                    <d:propstat><d:prop><d:resourcetype/><d:getetag>"e1"</d:getetag>
                    <d:getcontentlength>12</d:getcontentlength></d:prop></d:propstat></d:response>
                """)
            )
        )

        val entries = client().scan()

        val first = server.takeRequest()
        val second = server.takeRequest()
        val third = server.takeRequest()
        assertEquals("infinity", first.getHeader("Depth"))
        assertEquals("1", second.getHeader("Depth"))
        assertEquals("/dav/Quiet.md/", second.path)
        assertEquals("1", third.getHeader("Depth"))
        assertEquals("/dav/Quiet.md/Work/", third.path)
        assertTrue(entries.any { it.rel == "Work" && it.isDir })
        assertTrue(entries.any { it.rel == "Work/周报.md" && it.etag == "\"e1\"" })
    }

    @Test
    fun browse_lists_direct_folders_below_the_webdav_root() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                multistatus("""
                    <d:response><d:href>/dav/</d:href><d:propstat><d:prop>
                    <d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
                    <d:response><d:href>/dav/Quiet.md/</d:href><d:propstat><d:prop>
                    <d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
                    <d:response><d:href>/dav/%E7%AC%94%E8%AE%B0/</d:href><d:propstat><d:prop>
                    <d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
                    <d:response><d:href>/dav/readme.md</d:href><d:propstat><d:prop>
                    <d:resourcetype/><d:getcontentlength>3</d:getcontentlength></d:prop></d:propstat></d:response>
                """)
            )
        )

        val entries = client().browse("")

        val request = server.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("/dav/", request.path)
        assertEquals("1", request.getHeader("Depth"))
        assertEquals(listOf("Quiet.md", "笔记", "readme.md"), entries.map { it.rel })
    }

    @Test
    fun browse_nested_folder_uses_its_server_path_and_keeps_unicode_names() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                multistatus("""
                    <d:response><d:href>/dav/Quiet.md/</d:href><d:propstat><d:prop>
                    <d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
                    <d:response><d:href>/dav/Quiet.md/%E7%AC%94%E8%AE%B0/</d:href><d:propstat><d:prop>
                    <d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
                """)
            )
        )

        val entries = client().browse("Quiet.md")

        val request = server.takeRequest()
        assertEquals("/dav/Quiet.md/", request.path)
        assertEquals("1", request.getHeader("Depth"))
        assertEquals(listOf("Quiet.md/笔记"), entries.map { it.rel })
    }

    @Test(expected = WebDavException::class)
    fun unauthenticated_request_is_reported_as_auth_error() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
        client().probe()
    }

    @Test
    fun rate_limit_is_reported_with_a_retry_later_message() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody(""))

        try {
            client().browse("")
            org.junit.Assert.fail("expected WebDavException")
        } catch (e: WebDavException) {
            assertEquals(WebDavErrorKind.RATE_LIMITED, e.kind)
            assertTrue(e.message.contains("频率"))
        }
    }

    @Test
    fun request_budget_pauses_before_the_next_network_call() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                multistatus("""<d:response><d:href>/dav/Quiet.md/</d:href>
                    <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                    <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>""")
            )
        )
        val api = NutstoreWebDavClient(
            WebDavConfig.create(
                server = server.url("/dav/").toString(),
                username = "me@example.com",
                remoteRoot = "Quiet.md"
            ),
            "app-password",
            requestBudget = 1
        )

        api.probe()
        try {
            api.probe()
            org.junit.Assert.fail("expected SyncPausedException")
        } catch (e: SyncPausedException) {
            assertTrue(e.message.orEmpty().contains("请求上限"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun oversized_download_is_rejected_before_reading_the_body() {
        runBlocking {
            val limited = NutstoreWebDavClient(
                WebDavConfig.create(
                    server = server.url("/dav/").toString(),
                    username = "me@example.com",
                    remoteRoot = "Quiet.md"
                ),
                "app-password",
                maxDownloadBytes = 4
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("12345")
            )
            try {
                limited.get("a.md")
                org.junit.Assert.fail("expected SyncException")
            } catch (_: SyncException) {
            }
        }
    }

    private fun multistatus(responses: String): String =
        """<?xml version="1.0" encoding="utf-8"?>
           <d:multistatus xmlns:d="DAV:">$responses</d:multistatus>"""
}
