package com.mykara.export

import android.graphics.Canvas

/**
 * Nguồn khung hình. Chỉ phụ thuộc vào thời gian tMs (không giữ trạng thái),
 * nên seek/preview/export cho kết quả giống nhau. Sau này có thể thay bằng
 * bộ vẽ dựa trên WebView mà không sửa phần mã hóa.
 */
interface FrameRenderer {
    /** Chạy trên luồng dựng, trước mỗi khung (ví dụ chờ WebView vẽ xong). */
    fun prepare(tMs: Long) {}

    /** true = draw() phải chạy trên luồng UI (WebView chỉ vẽ được ở đó). */
    val drawOnMainThread: Boolean get() = false

    fun draw(canvas: Canvas, tMs: Long, width: Int, height: Int)
}
