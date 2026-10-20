package com.water.widget;

import android.app.Application;

/**
 * 进程入口。
 *
 * 只做一件事：把 Application Context 交给 {@link IlifeApi}，
 * 让 APP 链路能在任意线程读取本机官方「慧生活798」App 的版本号
 * （服务端用 VersionCode 做最低版本校验，官方 App 强制升级后必须跟随）。
 */
public class WaterApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        IlifeApi.installContext(this);
    }
}
