# Prototype render: nhạc + chữ → MP4 10 giây

Mục đích: kiểm tra phần rủi ro nhất của Mykara trên máy thật, đó là đường **vẽ khung hình → mã hóa H.264 phần cứng → ghép nhạc AAC → MP4**.

## Cách cài và chạy (chỉ cần điện thoại)

1. Mở https://github.com/Khang-ayart/Mykara/releases/tag/prototype-latest trên điện thoại.
2. Tải file `Mykara-prototype.apk` và mở nó để cài. Android sẽ hỏi quyền "Cài đặt ứng dụng không rõ nguồn gốc", hãy cho phép cho trình duyệt hoặc trình quản lý file đang dùng.
3. Mở app **Mykara Proto**.
4. (Tùy chọn) Bấm **Chọn file nhạc** để dùng bài thật. Bỏ qua thì app dùng tiếng bíp mỗi nửa giây, khớp với vòng tròn xung trên hình.
5. Chọn **720p** trước, bấm **Dựng thử 10 giây**. Sau đó thử **1080p**.
6. Khi xong, mở **Thư viện / Google Photos**, thư mục **Movies/Mykara**, xem video.

Yêu cầu: Android 10 trở lên.

## Cần xem gì trong video

- Chữ tiếng Việt có dấu hiển thị đúng, có phát sáng, hiện/mờ mượt.
- Emoji ☁️ bay vào ở câu 1; ❤️ ở câu 2 **vỡ thành 16 mảnh** văng ra đúng lúc 5,6 giây.
- Tiếng bíp trùng với lúc vòng tròn bắt đầu nở ra (kiểm tra hình và tiếng không lệch).
- Góc trái trên có đồng hồ `t=...s`; video phải dài đúng khoảng 10 giây.

## Gửi lại cho mình

Chụp màn hình phần nhật ký của app sau mỗi lần chạy (hoặc chép chữ, nhật ký chọn được). Các số quan trọng:

- `nhanh gấp ... lần thời gian thật` (trên 1,0 là dựng nhanh hơn thời gian thật);
- `bộ mã hóa ... (phần cứng: true/false)`;
- cảnh báo `số khung ghi ra khác số khung vẽ`, nếu có;
- video có giật, lệch tiếng, hoặc chữ/emoji sai không;
- máy nóng hay app bị đóng giữa chừng không.

## Giới hạn của prototype

- Vẽ bằng Canvas gốc của Android, **chưa qua WebView**. Bước tiếp theo sẽ thay bộ vẽ bằng canvas trong WebView (cùng giao diện `FrameRenderer`) để đo đúng đường chính thức.
- Khung được vẽ bằng Canvas phần cứng vào SurfaceTexture, rồi chép sang bộ mã hóa qua OpenGL (EGL) với mốc thời gian chuẩn (`GlFramePipe.kt`). Bản đầu (đóng dấu thời gian theo đồng hồ thật) làm bộ mã hóa MediaTek bỏ mất 11/300 khung nên đã bỏ.
- Âm thanh lấy 10 giây đầu bài; chưa chọn đoạn bắt đầu.
- Bản APK là bản debug, chỉ dùng thử.
