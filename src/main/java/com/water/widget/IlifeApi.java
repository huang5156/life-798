package com.water.widget;

import android.content.Context;
import android.content.pm.PackageInfo;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * 服务端 API 封装。
 *
 * 服务端有两条链路，请求头（User-Agent / ApplicationType / VersionCode）按链路区分：
 *
 *   请求头：
 *     User-Agent:      MAIN: WaterWidget/&lt;版本&gt; (Android)，APP: Android_ilife798_&lt;官方版本&gt;
 *     Content-Type:    application/json
 *     ApplicationType: 1,5（MAIN：积分 / 支付宝）或 1,1（APP：官方 App / 设备控制）
 *     VersionCode:     2.0.178（MAIN）或官方 App 版本（APP）
 *     Accept-Language: zh-Hans-CN;q=1
 *     Authorization:   &lt;token&gt;
 *
 * ⚠️ 服务端会用 VersionCode 做「最低版本」校验：官方 App 每次强制升级后，
 * 旧版本号会被直接拒绝并返回 msg=「请升级最新版APP」。所以 APP 链路的版本
 * 运行时会优先跟随本机已安装的官方「慧生活798」App，避免每次都要改代码重新打包。
 */
public class IlifeApi {
    static final String GATEWAY = BuildConfig.API_GATEWAY;
    static final String CID = BuildConfig.API_CID;
    private static final String SIGN_SALT = BuildConfig.SIGN_SALT;
    private static final String DEVICE_LOGIN_REJECTED_MESSAGE =
            "设备登录信息未被接受，请检查是否填反或重新完成设备登录";

    /**
     * 官方「慧生活798」App 的包名。
     *
     * 服务端的 VersionCode 头要的是**整数 versionCode**（官方 3.1.9 包为 {@code 31009}），
     * 而 User-Agent 里的版本是 versionName（{@code 3.1.9}）。两者不是同一个东西，
     * 之前把它们当成一个值（都用 3.1.9）上报，服务端按 versionCode 比较时就会判为过旧。
     */
    private static final String OFFICIAL_PACKAGE = "com.cloudora.android";
    /** 读到的官方版本缓存一分钟；读不到时只缓存 10 秒，便于刚装完/刚升级后尽快生效。 */
    private static final long OFFICIAL_VERSION_TTL_MS = 60_000L;
    private static final long OFFICIAL_VERSION_MISS_TTL_MS = 10_000L;

    private static volatile Context appContext;
    private static volatile OfficialVersion cachedOfficialVersion;
    private static volatile boolean cachedOfficialVersionFromDevice;
    private static volatile long cachedOfficialVersionAt;

    public interface ImgCallback {
        void onResult(byte[] bytes, String err);
    }

    public interface JsonCallback {
        void onResult(JSONObject json, String err);
    }

    public interface TextCallback {
        void onResult(String text, String err);
    }

    /**
     * 一条链路的协议常量。真实客户端对每条链路使用不同的 ApplicationType、
     * VersionCode 与 User-Agent，缺一不可。
     */
    static final class ApiProfile {
        final String appType;
        final String versionCode;
        final String userAgent;

        ApiProfile(String appType, String versionCode, String userAgent) {
            this.appType = appType;
            this.versionCode = versionCode;
            this.userAgent = userAgent;
        }
    }

    /** 官方 App 的两个版本标识：code 对应 VersionCode 头，name 对应 UA 后缀。 */
    static final class OfficialVersion {
        final String code;
        final String name;

        OfficialVersion(String code, String name) {
            this.code = code;
            this.name = name;
        }
    }

    /** MAIN 链路：积分 / 支付宝小程序。 */
    static final ApiProfile PROFILE_MAIN = new ApiProfile(
            "1,5", BuildConfig.VERSION_CODE_MAIN, BuildConfig.USER_AGENT_MAIN);

    /** APP 链路：官方 App / 设备控制 / 钱包。版本标识跟随本机官方 App。 */
    static ApiProfile appProfile() {
        OfficialVersion version = officialVersion();
        return new ApiProfile("1,1", version.code,
                BuildConfig.USER_AGENT_APP_PREFIX + version.name);
    }

    /** 由 Application 调用一次，之后无需到处传 Context。 */
    public static void installContext(Context ctx) {
        if (ctx != null && appContext == null) {
            appContext = ctx.getApplicationContext();
        }
    }

    /**
     * 本机官方 App 的版本标识：优先读已安装的官方 App，
     * 读不到（未安装 / 包可见性受限）时退回构建时注入的默认值。
     */
    static OfficialVersion officialVersion() {
        long now = System.currentTimeMillis();
        OfficialVersion cached = cachedOfficialVersion;
        long at = cachedOfficialVersionAt;
        if (cached != null && at != 0) {
            long ttl = cachedOfficialVersionFromDevice
                    ? OFFICIAL_VERSION_TTL_MS : OFFICIAL_VERSION_MISS_TTL_MS;
            if (now - at < ttl) return cached;
        }
        OfficialVersion detected = detectOfficialVersion();
        if (detected != null) {
            cachedOfficialVersion = detected;
            cachedOfficialVersionFromDevice = true;
        } else {
            detected = defaultOfficialVersion();
            cachedOfficialVersion = detected;
            cachedOfficialVersionFromDevice = false;
        }
        cachedOfficialVersionAt = now;
        return detected;
    }

    private static OfficialVersion defaultOfficialVersion() {
        return new OfficialVersion(BuildConfig.VERSION_CODE_APP, BuildConfig.APP_VERSION_NAME);
    }

    private static OfficialVersion detectOfficialVersion() {
        Context ctx = appContext;
        if (ctx == null) return null;
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(OFFICIAL_PACKAGE, 0);
            if (info == null) return null;
            String name = info.versionName == null ? "" : info.versionName.trim();
            long code = info.getLongVersionCode();
            if (name.isEmpty() && code <= 0) return null;
            if (code <= 0) {
                return new OfficialVersion(BuildConfig.VERSION_CODE_APP,
                        name.isEmpty() ? BuildConfig.APP_VERSION_NAME : name);
            }
            return new OfficialVersion(String.valueOf(code),
                    name.isEmpty() ? BuildConfig.APP_VERSION_NAME : name);
        } catch (Exception ignored) {
            // 没装官方 App（或系统限制包可见性）时退回默认值，不影响其它功能。
            return null;
        }
    }

    /**
     * 构造一条请求需要带的头。抽成纯函数便于单元测试锁定协议。
     */
    static Map<String, String> requestHeaders(ApiProfile profile, String token) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", profile.userAgent);
        headers.put("Content-Type", "application/json");
        headers.put("ApplicationType", profile.appType);
        headers.put("VersionCode", profile.versionCode);
        headers.put("Accept-Language", "zh-Hans-CN;q=1");
        if (token != null && !token.isEmpty()) {
            headers.put("Authorization", token);
        }
        return headers;
    }

    // ====== 签名 ======

    /**
     * 积分任务签名。
     * 实际算法通过 BuildConfig 注入的参数完成，源码中不含具体实现。
     * 本地构建时需在项目根目录创建 secrets.properties 并填入：
     *   API_GATEWAY=https://example.com/api/v1
     *   SIGN_SALT=your_salt_here
     *   API_CID=your_cid_here
     */
    public static String sign(String adId, String token, String uid) {
        if (SIGN_SALT == null || SIGN_SALT.isEmpty()) {
            return "";
        }
        return Signer.sign(adId, token, uid, SIGN_SALT);
    }

    // ====== 登录流程 ======

    public static String newCaptchaKey() {
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder();
        Random r = new Random();
        for (int i = 0; i < 10; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    public static void captcha(final String s, final ImgCallback cb) {
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                String u = GATEWAY + "/captcha/?s=" + URLEncoder.encode(s, "UTF-8")
                        + "&r=" + System.currentTimeMillis();
                c = (HttpURLConnection) new URL(u).openConnection();
                c.setRequestMethod("GET");
                c.setRequestProperty("User-Agent", PROFILE_MAIN.userAgent);
                c.setConnectTimeout(15000);
                c.setReadTimeout(15000);
                InputStream is = c.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
                cb.onResult(out.toByteArray(), null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

    public static void sendSms(final String phone, final String graphCode, final String s,
                               final JsonCallback cb) {
        JSONObject body = new JSONObject();
        try {
            body.put("un", phone);
            body.put("authCode", graphCode);
            body.put("s", s);
        } catch (Exception ignored) {}
        post("/acc/login/code", body, null, cb);
    }

    public static void login(final String phone, final String smsCode, final JsonCallback cb) {
        login(phone, smsCode, false, cb);
    }

    /**
     * 登录。
     * @param asApp true=用设备控制方式登录（ApplicationType=1,1）
     *              false=用账户服务方式登录（ApplicationType=1,5）
     */
    public static void login(final String phone, final String smsCode,
                             final boolean asApp, final JsonCallback cb) {
        JSONObject body = new JSONObject();
        try {
            body.put("openCode", "");
            body.put("authCode", smsCode);
            body.put("un", phone);
            body.put("cid", CID);
        } catch (Exception ignored) {}
        final ApiProfile profile = asApp ? appProfile() : PROFILE_MAIN;
        new Thread(() -> {
            try {
                String resp = httpRawApp("POST", GATEWAY + "/acc/login",
                        body.toString(), null, profile);
                cb.onResult(new JSONObject(resp), null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        }).start();
    }

    // ====== 账户信息 ======

    /** 取账户信息，含 uid（id 字段）。用于补全 Account.uid。 */
    public static void viewInfo(Context ctx, final JsonCallback cb) {
        get(ctx, "/acc/view-info", cb);
    }

    // ====== 积分任务 ======

    public static void missionLst(Context ctx, final JsonCallback cb) {
        get(ctx, "/acc/score/mission-lst", cb);
    }

    /** 用指定 token 获取任务列表（用于双平台合并）。 */
    public static void missionLstWithToken(final String token, final JsonCallback cb) {
        new Thread(() -> {
            try {
                String body = httpRaw("GET", GATEWAY + "/acc/score/mission-lst", null, token);
                cb.onResult(new JSONObject(body), null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        }).start();
    }

    /** 获取积分明细（用于校验任务是否已完成）。 */
    public static void scoreLst(final Context ctx, final JsonCallback cb) {
        get(ctx, "/acc/score/score-lst?page=0&size=200&hasCount=1", cb);
    }

    /** 用指定 token 获取账户信息（uid/eid/手机号）。 */
    public static void viewInfoWithToken(final String token, final JsonCallback cb) {
        new Thread(() -> {
            try {
                String body = httpRaw("GET", GATEWAY + "/acc/view-info", null, token);
                cb.onResult(new JSONObject(body), null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        }).start();
    }

    /**
     * 只读探测一条手动导入的登录信息。
     * 同时检查两种请求头下的账户信息、任务目录和设备首页，供本地判断登录平台。
     */
    public static void probeToken(final String token, final JsonCallback cb) {
        new Thread(() -> {
            JSONObject result = new JSONObject();
            String firstError = null;
            ApiProfile[] profiles = new ApiProfile[] { PROFILE_MAIN, appProfile() };
            String[][] requests = new String[][] {
                    {"viewMain", "/acc/view-info", "0"},
                    {"viewApp", "/acc/view-info", "1"},
                    {"missionMain", "/acc/score/mission-lst", "0"},
                    {"masterApp", "/ui/app/master", "1"}
            };
            for (String[] request : requests) {
                try {
                    ApiProfile profile = profiles[Integer.parseInt(request[2])];
                    String body = httpRawApp("GET", GATEWAY + request[1], null, token, profile);
                    result.put(request[0], new JSONObject(body));
                } catch (Exception e) {
                    if (firstError == null) firstError = e.getMessage();
                }
            }
            cb.onResult(result, firstError);
        }).start();
    }

    /** 用指定 token 获取积分明细。 */
    public static void scoreLstWithToken(final String token, final JsonCallback cb) {
        new Thread(() -> {
            try {
                String body = httpRaw("GET", GATEWAY + "/acc/score/score-lst?page=0&size=200&hasCount=1", null, token);
                cb.onResult(new JSONObject(body), null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        }).start();
    }

    /** 用设备控制登录信息获取最近账单；type=91 为设备消费。 */
    public static void billListWithToken(final String token, final JsonCallback cb) {
        requestApp("GET", "/bill/lst-owner?page=0&size=20", null, token, cb);
    }

    /** 使用设备控制登录信息获取账户钱包。 */
    public static void walletOwnerWithToken(final String appToken, final JsonCallback cb) {
        requestApp("GET", "/acc/wallet/owner", null, appToken, cb);
    }

    /** 使用设备登录信息读取可用积分。 */
    public static void accountScoreWithToken(final String appToken, final JsonCallback cb) {
        requestApp("GET", "/acc/score/acc-score", null, appToken, cb);
    }

    /** 兑换仅提交一次；网络结果不明确时由调用方核验，不自动重试。 */
    public static void exchangeScoreWithToken(final String appToken, final String endpointId,
                                              final int score, final JsonCallback cb) {
        requestApp("POST", "/acc/score/score-use",
                ScoreExchangeParser.INSTANCE.requestBody(endpointId, score), appToken, cb);
    }

    public static void exchangeBillWithToken(final String appToken, final String billId,
                                             final JsonCallback cb) {
        requestApp("GET", "/bill/view-full?id=" + enc(billId), null, appToken, cb);
    }

    /** 获取指定钱包端点的充值产品。 */
    public static void rechargeProductsWithToken(final String appToken,
                                                 final String endpointId,
                                                 final JsonCallback cb) {
        String path = "/prd/lst?eid=" + enc(endpointId)
                + "&type=1&status=1&all=false&did=&page=0&size=100&hasCount=false";
        requestApp("GET", path, null, appToken, cb);
    }

    /** 为指定钱包和充值产品创建订单。 */
    public static void createRechargeOrderWithToken(final String appToken,
                                                    final String endpointId,
                                                    final String ownerId,
                                                    final String productId,
                                                    final JsonCallback cb) {
        requestApp(
                "POST",
                "/bill/save",
                rechargeOrderBody(endpointId, ownerId, productId),
                appToken,
                cb
        );
    }

    /** 获取支付宝 SDK 所需的支付字符串。 */
    public static void prepayAlipayWithToken(final String appToken,
                                             final String orderId,
                                             final JsonCallback cb) {
        requestApp("GET", "/trans/prepay/21?id=" + enc(orderId), null, appToken, cb);
    }

    static JSONObject rechargeOrderBody(String endpointId, String ownerId, String productId) {
        try {
            return new JSONObject()
                    .put("cata", 1)
                    .put("contact", new JSONObject().put("id", ownerId))
                    .put("ep", new JSONObject().put("id", endpointId))
                    .put("note", "钱包充值")
                    .put("owner", new JSONObject().put("id", ownerId))
                    .put("prds", new org.json.JSONArray().put(
                            new JSONObject().put("id", productId).put("count", 1)
                    ));
        } catch (JSONException e) {
            throw new IllegalStateException("无法创建充值订单请求", e);
        }
    }

    /** 用指定 token 执行积分任务（用于双平台合并）。 */
    public static void scoreSendWithToken(final String token, final String uid,
                                          final String adId, final JsonCallback cb) {
        new Thread(() -> {
            try {
                String effectiveUid = uid;
                if (effectiveUid == null || effectiveUid.isEmpty()) {
                    effectiveUid = fetchUidSync(token);
                }
                final String sg = sign(adId, token, effectiveUid != null ? effectiveUid : "");
                String url = GATEWAY + "/acc/score/score-send?sign=" + sg + "&s=true";
                JSONObject body = new JSONObject();
                body.put("adId", adId);
                body.put("type", 101);
                String resp = httpRaw("POST", url, body.toString(), token);
                cb.onResult(new JSONObject(resp), null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        }).start();
    }

    /** 每日签到。weekDay: 1=周一...7=周日, signAdId: 签到adId(通常为"DAILY_CHECK_IN")。 */
    public static void scoreSendSignIn(final String token, final String uid,
                                       final int weekDay, final String signAdId,
                                       final JsonCallback cb) {
        new Thread(() -> {
            try {
                String effectiveUid = uid;
                if (effectiveUid == null || effectiveUid.isEmpty()) {
                    effectiveUid = fetchUidSync(token);
                }
                final String sg = sign(signAdId, token, effectiveUid != null ? effectiveUid : "");
                String url = GATEWAY + "/acc/score/score-send?sign=" + sg + "&s=true";
                JSONObject body = new JSONObject();
                body.put("weekDay", weekDay);
                body.put("adId", signAdId);
                String resp = httpRaw("POST", url, body.toString(), token);
                cb.onResult(new JSONObject(resp), null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        }).start();
    }

    /**
     * 完成任务（带签名）。
     * POST /acc/score/score-send?sign=<sign>&s=true  body={adId, type:101}
     */
    public static void scoreSend(final Context ctx, final String adId, final JsonCallback cb) {
        new Thread(() -> {
            Account acc = AccountStore.getCurrent(ctx);
            if (acc == null || !acc.hasToken()) {
                cb.onResult(null, "未登录");
                return;
            }
            // 确保 uid 存在
            String uid = acc.uid;
            if (uid == null || uid.isEmpty()) {
                // 同步取 uid
                uid = fetchUidSync(acc.token);
                if (uid != null && !uid.isEmpty()) {
                    acc.uid = uid;
                    AccountStore.updateCurrent(ctx, acc);
                }
            }
            final String sg = sign(adId, acc.token, uid != null ? uid : "");
            String url = GATEWAY + "/acc/score/score-send?sign=" + sg + "&s=true";
            JSONObject body = new JSONObject();
            try {
                body.put("adId", adId);
                body.put("type", 101);
            } catch (Exception ignored) {}
            try {
                String resp = httpRaw("POST", url, body.toString(), acc.token);
                cb.onResult(new JSONObject(resp), null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        }).start();
    }

    /** 同步获取 uid（从 view-info）。 */
    private static String fetchUidSync(String token) {
        try {
            String resp = httpRaw("GET", GATEWAY + "/acc/view-info", null, token);
            JSONObject json = new JSONObject(resp);
            if (json.optInt("code", -999) == 0) {
                JSONObject data = json.optJSONObject("data");
                if (data != null) return data.optString("id", "");
            }
        } catch (Exception ignored) {}
        return "";
    }

    // ====== 设备 ======

    public static void master(Context ctx, final JsonCallback cb) {
        new Thread(() -> {
            Account acc = AccountStore.getCurrent(ctx);
            boolean useApp = acc != null && acc.hasAppToken();
            String token = acc == null ? "" : (useApp ? acc.appToken : acc.token);
            ApiProfile profile = useApp ? appProfile() : PROFILE_MAIN;
            try {
                String body = httpRawApp("GET", GATEWAY + "/ui/app/master", null, token, profile);
                cb.onResult(new JSONObject(body), null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        }).start();
    }

    public static void devStart(Context ctx, final String did, final TextCallback cb) {
        new Thread(() -> {
            Account acc = AccountStore.getCurrent(ctx);
            if (acc == null || (!acc.hasToken() && !acc.hasAppToken())) {
                cb.onResult(null, "未登录");
                return;
            }
            // 设备控制只接受设备控制登录信息（ApplicationType=1,1）。
            // 用账户服务登录信息（1,5）请求设备接口，服务端会回一些与真实原因无关的提示。
            if (!acc.hasAppToken()) {
                cb.onResult(null, "需要设备登录信息，请先在账户中添加");
                return;
            }
            String url = GATEWAY + "/dev/start?did=" + enc(did)
                    + "&upgrade=true&ptype=91&rcp=false";
            try {
                ApiProfile profile = appProfile();
                String body = httpRawApp("GET", url, null, acc.appToken, profile);
                JSONObject json = new JSONObject(body);
                int code = json.optInt("code", -999);
                String msg = json.optString("msg", "");
                if (code == 0) {
                    cb.onResult("成功 ✓", null);
                } else if (code == -99) {
                    cb.onResult(null, "TOKEN_EXPIRED");
                } else {
                    cb.onResult(null, deviceErrorMessage(code, msg, profile));
                }
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        }).start();
    }

    /**
     * 使用调用时冻结的设备控制 Token 启动设备，避免异步请求期间切换当前账户后串用凭据。
     */
    public static void devStartWithToken(
            final String appToken,
            final String did,
            final TextCallback cb
    ) {
        new Thread(() -> {
            if (appToken == null || appToken.isEmpty()) {
                cb.onResult(null, "需要设备登录信息，请先在账户中添加");
                return;
            }
            String url = GATEWAY + "/dev/start?did=" + enc(did)
                    + "&upgrade=true&ptype=91&rcp=false";
            try {
                ApiProfile profile = appProfile();
                String body = httpRawApp("GET", url, null, appToken, profile);
                JSONObject json = new JSONObject(body);
                int code = json.optInt("code", -999);
                String msg = json.optString("msg", "");
                if (code == 0) {
                    cb.onResult("成功 ✓", null);
                } else if (code == -99) {
                    cb.onResult(null, "TOKEN_EXPIRED");
                } else {
                    cb.onResult(null, deviceErrorMessage(code, msg, profile));
                }
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        }).start();
    }

    /** 结束出水。 */
    public static void devEnd(Context ctx, final String did, final TextCallback cb) {
        new Thread(() -> {
            Account acc = AccountStore.getCurrent(ctx);
            if (acc == null || !acc.hasAppToken()) {
                cb.onResult(null, "需要设备登录信息，请先在账户中添加");
                return;
            }
            String url = GATEWAY + "/dev/end?did=" + enc(did);
            try {
                ApiProfile profile = appProfile();
                JSONObject json = new JSONObject(
                        httpRawApp("GET", url, null, acc.appToken, profile));
                int code = json.optInt("code", -999);
                if (code == 0) {
                    cb.onResult("已停止 ✓", null);
                } else if (code == -99) {
                    cb.onResult(null, "TOKEN_EXPIRED");
                } else {
                    cb.onResult(null, deviceErrorMessage(code, json.optString("msg", ""), profile));
                }
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        }).start();
    }

    /** 添加或移除账户收藏设备。remove=true 表示移除。 */
    public static void deviceFavorite(Context ctx, final String did, final boolean remove,
                                      final TextCallback cb) {
        new Thread(() -> {
            Account acc = AccountStore.getCurrent(ctx);
            if (acc == null || !acc.hasAppToken()) {
                cb.onResult(null, "需要设备登录信息，请先在账户中添加");
                return;
            }
            String url = GATEWAY + "/dev/favo?did=" + enc(did) + "&remove=" + (remove ? "1" : "0");
            try {
                ApiProfile profile = appProfile();
                JSONObject json = new JSONObject(
                        httpRawApp("GET", url, null, acc.appToken, profile));
                int code = json.optInt("code", -999);
                if (code == 0) {
                    cb.onResult(remove ? "已移除" : "已添加", null);
                } else if (code == -99) {
                    cb.onResult(null, "TOKEN_EXPIRED");
                } else {
                    cb.onResult(null, deviceErrorMessage(code, json.optString("msg", ""), profile));
                }
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        }).start();
    }

    /**
     * 设备类接口的错误码映射。
     *
     * 服务端在客户端版本过旧时返回 msg=「请升级最新版APP」（code 不属于下面这些已知值），
     * 这类提示本身没告诉用户该做什么，所以补一句可操作说明，并标出当前上报的版本号，
     * 方便判断是不是又遇到了服务端强制升级。
     */
    static String deviceErrorMessage(int code, String msg, ApiProfile profile) {
        switch (code) {
            case -21:
                return DEVICE_LOGIN_REJECTED_MESSAGE;
            case -88:
                return "未签约代扣协议，请在服务端完成签约后再使用";
            case -87:
                return "签约已过期，请重新签约";
            case -52:
                return "账户欠费，请充值后使用";
            case -20:
                return "未绑定一卡通账号，请先完成账户绑定";
            case -19:
                return "设备准备中，请稍后";
            case -82:
                return "需要支付";
            default:
                break;
        }
        String text = msg == null || msg.isEmpty() ? ("code=" + code) : msg;
        if (msg != null && (msg.contains("升级") || msg.contains("更新") || msg.contains("版本"))) {
            return text + "（当前上报版本 " + profile.versionCode
                    + "；请把官方「慧生活798」App 更新到最新版后重试）";
        }
        return text + "（code=" + code + "）";
    }

    // ====== 通用请求 ======

    private static void get(Context ctx, final String path, final JsonCallback cb) {
        new Thread(() -> {
            Account acc = AccountStore.getCurrent(ctx);
            String token = acc != null ? acc.token : "";
            try {
                String body = httpRaw("GET", GATEWAY + path, null, token);
                cb.onResult(new JSONObject(body), null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        }).start();
    }

    private static void post(final String path, final JSONObject body, final String token,
                             final JsonCallback cb) {
        new Thread(() -> {
            try {
                String resp = httpRaw("POST", GATEWAY + path,
                        body != null ? body.toString() : null, token);
                cb.onResult(new JSONObject(resp), null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        }).start();
    }

    private static void requestApp(final String method, final String path,
                                   final JSONObject body, final String appToken,
                                   final JsonCallback cb) {
        new Thread(() -> {
            try {
                String response = httpRawApp(
                        method,
                        GATEWAY + path,
                        body != null ? body.toString() : null,
                        appToken,
                        appProfile()
                );
                cb.onResult(new JSONObject(response), null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        }).start();
    }

    private static String httpRaw(String method, String urlStr, String body, String token)
            throws Exception {
        return httpRawApp(method, urlStr, body, token, PROFILE_MAIN);
    }

    /** 同 httpRaw，但可指定链路（决定 User-Agent / ApplicationType / VersionCode）。 */
    private static String httpRawApp(String method, String urlStr, String body,
                                     String token, ApiProfile profile) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            for (Map.Entry<String, String> header : requestHeaders(profile, token).entrySet()) {
                c.setRequestProperty(header.getKey(), header.getValue());
            }
            if (body != null) {
                c.setDoOutput(true);
                OutputStream os = c.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.flush();
                os.close();
            }
            int http = c.getResponseCode();
            BufferedReader rd = new BufferedReader(new InputStreamReader(
                    http >= 400 ? c.getErrorStream() : c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = rd.readLine()) != null) sb.append(line);
            rd.close();
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
