package com.example.tiktokxsleppify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {

    @Test
    fun allowsTikTokAndSubdomains() {
        assertTrue(UrlPolicy.isAllowedNavigation("https://www.tiktok.com/foryou"))
        assertTrue(UrlPolicy.isAllowedNavigation("https://tiktok.com/login/qrcode"))
        assertTrue(UrlPolicy.isAllowedNavigation("https://v16-webapp.tiktokcdn.com/video"))
    }

    @Test
    fun blocksLookAlikeAndForeignHosts() {
        assertFalse(UrlPolicy.isAllowedNavigation("https://nottiktok.com/"))
        assertFalse(UrlPolicy.isAllowedNavigation("https://tiktok.com.evil.example/"))
        assertFalse(UrlPolicy.isAllowedNavigation("https://box.com/"))
        assertFalse(UrlPolicy.isAllowedNavigation("https://example.org/"))
    }

    @Test
    fun blocksNonHttpSchemes() {
        assertFalse(UrlPolicy.isAllowedNavigation("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(UrlPolicy.isAllowedNavigation("file:///sdcard/x.html"))
        assertFalse(UrlPolicy.isAllowedNavigation("javascript:alert(1)"))
        assertFalse(UrlPolicy.isAllowedNavigation(null))
        assertFalse(UrlPolicy.isAllowedNavigation(""))
    }

    @Test
    fun allowsLoginProviders() {
        assertTrue(UrlPolicy.isAllowedNavigation("https://www.facebook.com/v2.0/dialog/oauth"))
        assertTrue(UrlPolicy.isAllowedNavigation("https://appleid.apple.com/auth/authorize"))
        assertTrue(UrlPolicy.isAllowedNavigation("https://x.com/i/oauth2/authorize"))
    }

    @Test
    fun detectsGoogleAuth() {
        assertTrue(UrlPolicy.isGoogleAuth("https://accounts.google.com/o/oauth2/v2/auth"))
        assertFalse(UrlPolicy.isGoogleAuth("https://www.tiktok.com/foryou"))
    }

    @Test
    fun detectsTrackers() {
        assertTrue(UrlPolicy.isTracker("mon-va.tiktokv.com"))
        assertTrue(UrlPolicy.isTracker("www.googletagmanager.com"))
        assertFalse(UrlPolicy.isTracker("www.tiktok.com"))
        assertFalse(UrlPolicy.isTracker(null))
    }
}
