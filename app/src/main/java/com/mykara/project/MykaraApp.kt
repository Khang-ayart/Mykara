package com.mykara.project

import android.app.Application

/** Khởi động thư viện font người dùng một lần để mọi màn hình (xem trước, xuất video) thấy cùng danh sách. */
class MykaraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        UserFonts.init(this)
    }
}
