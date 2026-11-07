package com.theoriacodex.sources.custom

import com.theoriacodex.domain.source.ProbeFailure
import com.theoriacodex.sources.http.SourceHttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeEvidenceTest {
    @Test
    fun `Danbooru's error envelope is auth evidence only on 401 and 403`() {
        val invalidKey = fixture("aibooru/invalid_api_key_401.json")
        val accessDenied = fixture("aibooru/access_denied_403.json")

        assertTrue(SourceHttpResponse(401, invalidKey).isDanbooruAuthRefusal())
        assertTrue(SourceHttpResponse(403, accessDenied).isDanbooruAuthRefusal())
        assertFalse(SourceHttpResponse(404, fixture("aibooru/moebooru_post.json")).isDanbooruAuthRefusal())
    }

    @Test
    fun `HTTP authentication and HTML denial pages are not Gelbooru refusals`() {
        val basicAuth = SourceHttpResponse(401, "", mapOf("WWW-Authenticate" to listOf("Basic realm=\"site\"")))
        val deniedPage = SourceHttpResponse(403, "<!DOCTYPE html><html><title>Access denied</title></html>")

        assertFalse(basicAuth.isGelbooruAuthRefusal())
        assertFalse(deniedPage.isGelbooruAuthRefusal())
        assertTrue(SourceHttpResponse(401, "").isGelbooruAuthRefusal())
        assertEquals(FamilyProbe.Failed(ProbeFailure.BLOCKED), deniedPage.unclaimed())
    }

    @Test
    fun `an empty Gelbooru site still shows its envelope`() {
        assertTrue(SourceHttpResponse(200, """<?xml version="1.0" encoding="UTF-8"?><posts count="0" offset="0"></posts>""").isGelbooruPostsEnvelope())
        assertTrue(SourceHttpResponse(200, """<posts limit="1" offset="0" count="12">""").isGelbooruPostsEnvelope())
        assertFalse(SourceHttpResponse(200, "<html><posts count=\"1\">").isGelbooruPostsEnvelope())
    }

    @Test
    fun `a verification challenge page is blocked, whatever its status`() {
        val challenge = fixture("challenge_gate/verification_challenge_200.html")

        assertTrue(SourceHttpResponse(200, challenge).isVerificationChallenge())
        assertEquals(FamilyProbe.Failed(ProbeFailure.BLOCKED), SourceHttpResponse(200, challenge).unclaimed())
        assertEquals(FamilyProbe.Failed(ProbeFailure.BLOCKED), SourceHttpResponse(503, challenge).unclaimed())
    }

    @Test
    fun `ordinary pages are not verification challenges`() {
        assertFalse(SourceHttpResponse(200, "<!doctype html><p>we accept the challenge and a powerful counter</p></html>").isVerificationChallenge())
        assertFalse(SourceHttpResponse(200, "<!doctype html><noscript>JavaScript is disabled. Please enable it.</noscript></html>").isVerificationChallenge())
        assertEquals(FamilyProbe.NoMatch, SourceHttpResponse(200, "<!doctype html><p>we accept the challenge and a powerful counter</p></html>").unclaimed())
    }

    private fun fixture(path: String) = FixtureSiteTransport.resource(path).decodeToString()
}
