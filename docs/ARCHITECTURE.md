# Kiến trúc Mykara

## 1. Pipeline

| Bước | Đầu vào | Đầu ra |
|---|---|---|
| Phân tích | nhạc, ảnh | nhịp, đường bass, đoạn nhạc, bảng màu, điểm nhấn ảnh |
| Đồng bộ | nhạc + lyric | timestamp từng câu/từ + độ tin cậy |
| Tự thiết kế | kết quả trên | template, hiệu ứng từng câu, bố cục, chuyển cảnh |
| Dựng cảnh | tất cả | Scene JSON |

Mỗi bước chỉ đọc đầu ra của bước trước. Preview và Export dùng chung Scene JSON + engine.

## 2. Cấu trúc dự án

```
project/
  project.json   nguồn dữ liệu chính
  assets/        ảnh, nhạc gốc
  cache/         dữ liệu suy ra, xóa được, tự tạo lại (khóa theo mã băm file)
```

Khối trong `project.json`: `meta`, `assets`, `lyrics`, `timing`, `design`, `export`.
Mỗi giá trị do thuật toán sinh ra có `origin` (`auto` | `user`) và `confidence`.

**Quy tắc:** chạy lại tự động không ghi đè `origin: user`.

## 3. Cập nhật từng phần (đồ thị phụ thuộc)

| Thay đổi | Tính lại |
|---|---|
| Sửa chữ một câu | căn lại câu đó, hiệu ứng câu đó |
| Kéo timestamp một câu | chỉ câu đó, đặt `origin: user` |
| Đổi template | chỉ `design`, giữ nguyên `timing` |
| Đổi ảnh | phân tích ảnh, gợi ý lại màu/bố cục (hỏi trước khi áp dụng) |
| Đổi file nhạc | phân tích + đồng bộ + design |

## 4. Tự động và cần người dùng

**Tự động:** phát hiện nhịp/bass/đoạn nhạc, bảng màu, chọn template, gán hiệu ứng, chuyển cảnh, đồng bộ các câu độ tin cậy cao.

**Cần xác nhận/chỉnh:**
- Câu đồng bộ độ tin cậy thấp (gắn cờ, nghe thử từng câu).
- Lyric nhập sai hoặc thiếu so với bài thật.
- Đoạn dạo, rap nhanh, bè, lời lặp.
- Lựa chọn thẩm mỹ (template, vị trí chữ, ảnh theo đoạn): app đề xuất, người dùng quyết định.

Đồng bộ tự động thất bại hoàn toàn → chế độ gõ nhịp (nghe và chạm khi mỗi câu bắt đầu).

## 5. Quyết định kỹ thuật

- Render: canvas/WebGL → MediaCodec (H.264 phần cứng); FFmpeg chỉ ghép/chuyển đổi âm thanh. Cần prototype đo thực tế.
- Preview thời gian thực độ phân giải thấp; Export chạy nền, full chất lượng.
- Whisper/Forced Alignment nằm sau một interface chung (cục bộ hoặc máy chủ); bản đầu ưu tiên máy chủ.
- Template khai báo bằng JSON; hiệu ứng là plugin đăng ký theo tên.

## 6. Chữ tượng hình → hình (pictogram swap)

- Bước **Tự thiết kế** dò từ tượng hình (mây, mưa, gió, trăng...) theo `data/pictograms.json`, khớp cụm dài trước, và **mặc định thay chữ bằng hình** (`mode: replace`).
- Kết quả lưu trong `design.lines[].swaps` (theo token của câu), có `origin: auto` và `enabled: true`.
- Ở **Preview**, người dùng chạm vào hình để: giữ chữ (`enabled: false`), hiện hình bên cạnh chữ (`beside`), đổi hình khác, hoặc dùng ảnh riêng. Mọi thay đổi đặt `origin: user`, chạy lại tự động không ghi đè.
- Chỉ tính lại câu bị ảnh hưởng. Scene JSON nhận `icon`/`iconMode` ở từng từ.
- Rủi ro: nghĩa bóng hoặc từ đồng âm ("sao" = vì sao / star) có thể thay sai, nên độ tin cậy thấp thì để chữ và chỉ gợi ý. Bộ hình (icon set) chưa chọn, hiện mới là id.
