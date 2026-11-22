package com.aisub.player.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAuthTest {

    @Test
    fun `detects 115 and major cloud auth expiration messages`() {
        assertTrue(ServerAuth.isServerAuthError("115 登录已失效，请重新扫码"))
        assertTrue(ServerAuth.isServerAuthError("阿里云盘登录已失效，请重新扫码"))
        assertTrue(ServerAuth.isServerAuthError("百度网盘授权已失效，请重新登录（errno=-6）"))
        assertTrue(ServerAuth.isServerAuthError("OneDrive 登录已失效，请重新登录"))
        assertTrue(ServerAuth.isServerAuthError("Plex 登录已失效，请重新登录"))
        assertTrue(ServerAuth.isServerAuthError("Emby 登录已失效，请重新登录"))
        assertTrue(ServerAuth.isServerAuthError("PikPak 登录已失效，请重新登录（unauthenticated）"))
        assertTrue(ServerAuth.isServerAuthError("音乐库登录失效或无权限（HTTP 401）"))
        assertTrue(ServerAuth.isServerAuthError("用户名或密码错误"))
        assertTrue(ServerAuth.isServerAuthError("OpenList 请求失败 HTTP 401"))
        assertTrue(ServerAuth.isServerAuthError("服务器不让访问，多半是登录过期了。到「浏览」里对这个位置选「重新登录」"))
    }

    @Test
    fun `ignores non-auth network and file errors`() {
        assertFalse(ServerAuth.isServerAuthError("连接超时，请检查网络"))
        assertFalse(ServerAuth.isServerAuthError("服务器上已经没有这个文件了（HTTP 404）"))
        assertFalse(ServerAuth.isServerAuthError(null))
        assertFalse(ServerAuth.isServerAuthError(""))
    }

    @Test
    fun `maps server types to appropriate reauth button labels`() {
        assertEquals("重新扫码登录", ServerAuth.reauthButtonLabel(ServerType.PAN115))
        assertEquals("重新扫码登录", ServerAuth.reauthButtonLabel(ServerType.ALIYUN))
        assertEquals("重新登录", ServerAuth.reauthButtonLabel(ServerType.PLEX))
        assertEquals("重新登录", ServerAuth.reauthButtonLabel(ServerType.ONEDRIVE))
        assertEquals("修改账号密码", ServerAuth.reauthButtonLabel(ServerType.WEBDAV))
    }

    @Test
    fun `resolves server id from cloud and webdav playback uris`() {
        val s115 = Server(id = "srv-115", type = ServerType.PAN115, name = "115 网盘", baseUrl = "")
        val sDav = Server(id = "srv-dav", type = ServerType.WEBDAV, name = "NAS", baseUrl = "https://nas.example.com/dav")
        val servers = listOf(s115, sDav)

        assertEquals("srv-115", ServerAuth.serverIdForMediaUri("cloud://srv-115/pick123/movie.mkv", servers))
        assertEquals("srv-dav", ServerAuth.serverIdForMediaUri("https://nas.example.com/dav/Movies/a.mp4", servers))
        assertNull(ServerAuth.serverIdForMediaUri("content://media/external/video/media/42", servers))
    }
}
