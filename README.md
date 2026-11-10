# Mykara

Ứng dụng Android tạo video lyric nghệ thuật: nhập ảnh, nhạc, lời bài hát → app tự đồng bộ lời và tự thiết kế video → xem trước → chỉnh sửa (tùy chọn) → lưu dự án → xuất MP4.

## Luồng chính

Nhập tài nguyên → Phân tích → Đồng bộ lời → Tự thiết kế → **Preview** → (Chỉnh sửa) → Lưu dự án → Xuất video

## Nguyên tắc

- Người dùng không cần tự tạo LRC hay căn lời thủ công từ đầu.
- Preview có trước bước chỉnh sửa; có thể bỏ qua chỉnh sửa.
- Dự án lưu từng thành phần riêng (ảnh, nhạc, lời, timestamp, template, hiệu ứng), không chỉ video đã render.
- Sửa một thành phần chỉ cập nhật phần bị ảnh hưởng.
- Preview và Export dùng chung một Scene JSON và một engine.
- Giá trị người dùng đã chỉnh (`origin: user`) không bao giờ bị ghi đè bởi xử lý tự động.

## Cấu trúc repo

```
docs/
  PROPOSAL.md       Đề xuất dự án gốc
  ARCHITECTURE.md   Kiến trúc theo luồng mới
  project.example.json  Ví dụ project.json
app/                (sẽ tạo ở bước sau) Android + WebView
```

## Lộ trình

1. Schema `project.json` và Scene JSON  ← đang ở đây
2. Prototype render: canvas chữ + nhạc → MP4 10 giây (MediaCodec) trên máy thật
3. Khung app Android (WebView + cầu nối Kotlin), nhập tài nguyên, lưu/mở dự án
4. Animation engine + 5 template
5. Audio reactive
6. Preview + Export hoàn chỉnh
7. Smart Sync (Whisper + Forced Alignment), có hàng đợi xác nhận câu độ tin cậy thấp
