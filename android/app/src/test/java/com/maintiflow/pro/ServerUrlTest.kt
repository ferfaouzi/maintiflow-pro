package com.maintiflow.pro

import com.maintiflow.pro.data.SessionStore
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerUrlTest {
    @Test
    fun normalizesServerUrl() {
        assertEquals("https://mf.usine.local/api/v1/", SessionStore.normalize("mf.usine.local"))
        assertEquals("https://mf.usine.local:8443/api/v1/", SessionStore.normalize("https://mf.usine.local:8443/"))
        assertEquals("http://10.0.2.2:8000/api/v1/", SessionStore.normalize("http://10.0.2.2:8000/api/v1"))
        assertEquals("", SessionStore.normalize("  "))
    }
}
