# Kết quả prototype render (máy thật)

Máy: Xiaomi 2404ARN45A, Android 16, chip MT6768 (tầm trung). Cảnh thử 10 giây, 720p, 30 FPS, bộ mã hóa phần cứng `c2.mtk.avc.encoder`.

| Bộ vẽ | Khung | Tổng | So với thời gian thật | Vẽ mỗi khung | File |
|---|---|---|---|---|---|
| Canvas gốc | 300/300 | 7,9 s | nhanh gấp 1,26 | 10,2 ms | 6,1 MB |
| WebView – vẽ trực tiếp | 300/300 | 18,8 s | chậm hơn (0,53) | 46,8 ms | 6,1 MB |
| WebView – qua ảnh JPEG | 300/300 | 36,2 s | chậm hơn (0,28) | 102,1 ms | 6,1 MB |

Các lần thử thất bại trên đường đi (đã sửa hoặc bỏ):
- Mốc thời gian theo đồng hồ thật làm bộ mã hóa bỏ 11/300 khung → sửa bằng EGL với mốc thời gian chuẩn (`GlFramePipe.kt`).
- Chụp WebView vào Bitmap phần mềm → màn đen trên Android 16 → bỏ.

## Quyết định

1. **Đường chính thức: WebView vẽ trực tiếp** (`WebViewFrameRenderer`, mode DIRECT). Một engine JavaScript dùng cho cả preview (chạy tự nhiên trong WebView) và export, nên hai bên khớp nhau. Chi phí: khoảng 2,4 lần chậm hơn Canvas gốc.
2. Chế độ JPEG chỉ giữ làm đường dự phòng, quá chậm để dùng chính.
3. Canvas gốc (Kotlin) giữ làm chuẩn đo, không phát triển thêm engine song song bằng Kotlin.

## Việc cần tối ưu / chưa biết

- Thời gian ước lượng cho bài 4 phút (thô, cảnh thật nặng hơn cảnh thử): WebView trực tiếp cỡ 7–8 phút, Canvas gốc cỡ 3 phút ở 720p. **Chưa đo 1080p.**
- 46,8 ms/khung của WebView phần lớn có thể là hai lần chờ `requestAnimationFrame`; thử giảm còn một lần hoặc bỏ chờ.
- Mã hóa AAC 10 giây nhạc mất khoảng 4,3–4,8 s (chậm hơn thời gian thật); với bài 4 phút có thể thành khoảng 100 s. Chạy song song nên chưa chặn, nhưng là mức sàn cần xem lại.
- Cảnh thử chưa có nhiều lớp (ảnh nền lớn, hạt, blur); cần đo lại với cảnh thật.
