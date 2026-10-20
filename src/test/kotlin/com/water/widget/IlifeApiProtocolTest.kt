package com.water.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定两条链路的请求头协议。
 *
 * 服务端要求每条请求都带 ApplicationType / VersionCode，并按 VersionCode 做最低版本校验：
 * - MAIN（积分链路）用官方小程序的 2.0.178；
 * - APP（设备控制）用官方 App 的**整数 versionCode**（3.1.9 包 = 31009），
 *   User-Agent 里才是 versionName（3.1.9）。
 *
 * 公开仓库原来的实现两项都不发、UA 也共用，表现为接水时提示「请升级最新版APP」。
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
    fun `APP 链路用整数 versionCode，UA 用 versionName`() {
        val version = IlifeApi.officialVersion()
        val profile = IlifeApi.appProfile()
        val headers = IlifeApi.requestHeaders(profile, "token-app")

        assertEquals("1,1", headers["ApplicationType"])
        assertEquals(version.code, headers["VersionCode"])
        assertEquals(BuildConfig.USER_AGENT_APP_PREFIX + version.name, headers["User-Agent"])
        assertEquals("token-app", headers["Authorization"])
    }

    @Test
    fun `VersionCode 头必须是纯数字，不能是点分的版本名`() {
        val code = IlifeApi.officialVersion().code
        assertTrue("VersionCode 应为整数 versionCode，实际为 $code", code.all { it.isDigit() })
        assertNotEquals(BuildConfig.APP_VERSION_NAME, code)
    }

    @Test
    fun `读不到官方 App 时退回构建期默认值`() {
        // 单元测试里没有 Application Context，等同于「没装官方 App」。
        val version = IlifeApi.officialVersion()
        assertEquals(BuildConfig.VERSION_CODE_APP, version.code)
        assertEquals(BuildConfig.APP_VERSION_NAME, version.name)
        assertFalse(version.code.isEmpty())
        assertFalse(version.name.isEmpty())
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
        assertTrue("应带上当前上报的版本号", message.contains(profile.versionCode))
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
