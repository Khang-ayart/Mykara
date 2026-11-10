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

- Render: WebView (canvas) vẽ trực tiếp → EGL → MediaCodec (H.264 phần cứng), đã đo trên máy thật (xem `PROTOTYPE_RESULTS.md`). FFmpeg chỉ ghép/chuyển đổi âm thanh nếu cần.
- Preview thời gian thực độ phân giải thấp; Export chạy nền, full chất lượng.
- Whisper/Forced Alignment nằm sau một interface chung (cục bộ hoặc máy chủ); bản đầu ưu tiên máy chủ.
- Template khai báo bằng JSON; hiệu ứng là plugin đăng ký theo tên.

## 6. Semantic Visual Replacement (lớp hình ảnh theo ngữ nghĩa)

Thay từ/cụm từ tượng hình (mây, mưa, gió, "trái tim vỡ tan"...) bằng hình có hoạt ảnh đúng nghĩa.

**Lớp độc lập.** `design.semanticLayer` có công tắc `enabled` riêng và danh sách `items`. Tắt cả lớp thì video chỉ còn chữ. Trong Scene JSON đây là layer `type: "semantic"`, tách khỏi layer chữ.

**Một item** gồm: cụm token (`tokenStart..tokenEnd`), `visual` (emoji / vector / ảnh người dùng), `phases` và `textMode`.
- `phases` bám theo từ: "trái tim vỡ tan" = `popIn` ở token 0, `shatter` ở token 2 ("vỡ"). Thời điểm tuyệt đối tính từ timestamp từng từ; nếu chưa có timestamp từ thì chia đều trong câu.
- `textMode`: `replace` (ẩn chữ), `beside` (chữ và hình cùng hiện), `hidden` (quay lại chữ, không hiện hình).

**Preview.** Người dùng giữ nguyên, đổi hình, đổi hoạt ảnh (preset), chuyển `textMode`, hoặc tắt từng item. Sửa là đặt `origin: user`, chạy lại tự động không ghi đè. Chỉ item/câu bị sửa mới tính lại.

**Thư viện** (`data/semantic_library.json`): từ khóa/cụm → hình + phases mặc định, khớp cụm dài trước. Từ nhiều nghĩa (ví dụ "sao") đánh dấu `ambiguous`: độ tin cậy thấp thì chỉ gợi ý, không tự thay.

### Giữ cho dựng video không nặng

1. **Preset là hàm theo thời gian, không giữ trạng thái** `f(t, params, seed)`. Seek, preview và export cho cùng kết quả, không cần mô phỏng từng khung.
2. **Hình dựng sẵn một lần** vào canvas ngoài màn hình rồi cache; mỗi khung chỉ vẽ lại với biến đổi (dịch, xoay, co giãn, mờ).
3. **Phân loại chi phí preset:** `cheap` (chỉ biến đổi), `particles` (mưa, gió, tuyết), `shards` (vỡ). Chỉ item đang hiện mới tốn tài nguyên.
4. **Ngân sách** (`budget`): tối đa `maxConcurrent` item cùng lúc (mặc định 3) và `maxParticles` hạt (mặc định 150). Vượt thì giảm số hạt, không rớt khung hình.
5. **Mảnh vỡ tính trước một lần** cho mỗi hình (chia mảnh khi tạo cache), lúc vỡ chỉ nội suy vị trí.
6. **`seed`** trong Scene giữ ngẫu nhiên (hạt, mảnh vỡ) giống nhau giữa preview và export.
7. Preview có thể giảm số hạt và độ phân giải; export dùng đủ chất lượng.

### Tài nguyên thử nghiệm và rủi ro
- Emoji dùng được để thử nhanh, nhưng hình emoji phụ thuộc font của máy, nên không đồng nhất giữa thiết bị và khó vỡ mảnh đẹp. Thư viện vector (SVG) đi kèm app là hướng chính; emoji chỉ là bản tạm.
- Hoạt ảnh phức tạp hơn (nhân vật, cảnh) về sau có thể dùng sprite hoặc Lottie; schema hiện đã có `kind` để mở rộng, nhưng chưa làm.
