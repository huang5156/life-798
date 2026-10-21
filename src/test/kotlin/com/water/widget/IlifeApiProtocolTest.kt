package com.water.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定两条链路的请求协议。
 *
 * 官方客户端（`com.ilife.lib.coremodel.http.base.OkHelper`）会强制注入：
 * - MAIN（积分链路）：VersionCode = 2.0.178，UA = WaterWidget/5.4.4 (Android)
 * - APP（设备控制）：VersionCode = 版本名（3.1.9），UA = Android_ilife798_3.1.9
 *
 * 出水接口 `/dev/start` 还要带 `cnt=1`（DevControlHelper 的通道计数）。
 * 缺任一项，服务端都会回到「请升级最新版APP」的旧客户端分支。
 */
class IlifeApiProtocolTest {

    @Test
    fun `MAIN 链路使用账户服务的头`() {
        val headers = IlifeApi.requestHeaders(IlifeApi.PROFILE_MAIN, "token-main")

        assertEquals("1,5", headers["ApplicationType"])
        assertEquals(BuildConfig.VERSION_CODE_MAIN, headers["VersionCode"])
        assertEquals(BuildConfig.USER_AGENT_MAIN, headers["User-Agent"])
        assertEquals("application/json", headers["Content-Type"])
        assertEquals("zh-Hans-CN;q=1", headers["Accept-Language"])
        assertEquals("token-main", headers["Authorization"])
    }

    @Test
    fun `APP 链路 VersionCode 与 UA 都用官方版本名`() {
        val version = IlifeApi.officialVersion()
        val headers = IlifeApi.requestHeaders(IlifeApi.appProfile(), "token-app")

        assertEquals("1,1", headers["ApplicationType"])
        assertEquals(version, headers["VersionCode"])
        assertEquals("Android_ilife798_$version", headers["User-Agent"])
        assertEquals("token-app", headers["Authorization"])
    }

    @Test
    fun `VersionCode 是版本名而不是整数 versionCode`() {
        val version = IlifeApi.officialVersion()

        assertTrue("版本名形如 3.1.9，实际为 $version", version.matches(Regex("""\d+\.\d+\.\d+""")))
        assertEquals(IlifeApi.OFFICIAL_VERSION, version)
        assertFalse("不应再上报 31009 这类整数 versionCode", version.all { it.isDigit() })
    }

    @Test
    fun `读不到官方 App 时退回构建期默认值`() {
        // 单元测试里没有 Application Context，等同于「没装官方 App」。
        assertEquals(BuildConfig.APP_VERSION_NAME, IlifeApi.officialVersion())
        assertEquals(IlifeApi.OFFICIAL_VERSION, BuildConfig.APP_VERSION_NAME)
    }

    @Test
    fun `出水接口带 cnt 通道计数`() {
        val url = IlifeApi.deviceStartUrl("ABC123")

        assertTrue(url, url.contains("/dev/start?did=ABC123"))
        assertTrue(url, url.contains("&upgrade=true"))
        assertTrue(url, url.contains("&ptype=91"))
        assertTrue(url, url.contains("&rcp=false"))
        assertTrue("缺少 cnt=1 会被服务端当作旧客户端", url.contains("&cnt=1"))
    }

    @Test
    fun `没有登录信息时不发 Authorization`() {
        val headers = IlifeApi.requestHeaders(IlifeApi.PROFILE_MAIN, "")
        assertFalse(headers.containsKey("Authorization"))
        assertFalse(IlifeApi.requestHeaders(IlifeApi.PROFILE_MAIN, null).containsKey("Authorization"))
    }

    @Test
    fun `版本过旧的提示会被补上可操作说明`() {
        val profile = IlifeApi.appProfile()
        val message = IlifeApi.deviceErrorMessage(-1, "请升级最新版APP", profile)

        assertTrue(message.contains("请升级最新版APP"))
        assertTrue("应提示更新官方 App", message.contains("官方"))
        assertTrue("应带上当前上报的版本", message.contains(profile.versionCode))
    }

    @Test
    fun `已知错误码保持原有文案`() {
        val profile = IlifeApi.appProfile()
        assertEquals(
            "设备登录信息未被接受，请检查是否填反或重新完成设备登录",
            IlifeApi.deviceErrorMessage(-21, "", profile)
        )
        assertEquals("账户欠费，请充值后使用", IlifeApi.deviceErrorMessage(-52, "", profile))
        assertEquals("设备准备中，请稍后", IlifeApi.deviceErrorMessage(-19, "", profile))
    }

    @Test
    fun `未知错误码会带上 code`() {
        val profile = IlifeApi.appProfile()
        assertEquals("神秘错误（code=-7）", IlifeApi.deviceErrorMessage(-7, "神秘错误", profile))
        assertEquals("code=-7", IlifeApi.deviceErrorMessage(-7, "", profile))
    }
}
