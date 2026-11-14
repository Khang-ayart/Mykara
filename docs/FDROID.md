# Đưa Mykara lên F-Droid

Trạng thái: mã nguồn đã sẵn sàng; còn các bước dưới đây do chủ repo làm (cần tài khoản GitLab và quyền tạo tag).

## Đã chuẩn bị trong repo

- `applicationId` = `com.mykara.app`, giấy phép GPL-3.0 (`LICENSE`), phông chữ OFL (kèm `OFL-*.txt`, ghi công trong màn Ủng hộ).
- Không có quyền Internet, không có bộ tự cập nhật, không có SDK theo dõi hay quảng cáo; chỉ dùng AndroidX (`core-ktx`, `activity-ktx`).
- `fastlane/metadata/android/{vi,en-US}/`: tên, mô tả ngắn/dài, changelog (`changelogs/<versionCode>.txt`). Ảnh chụp màn hình để ở `images/phoneScreenshots/`.
- `fdroid/com.mykara.app.yml`: bản nháp metadata để nộp cho `fdroiddata`.
- `app/build.gradle.kts`: bản phát hành lấy `releaseCode`/`releaseName`; CI GitHub tự cộng 1000 vào số build nên không đụng số của bản phát hành.

## Các bước nộp

1. Mỗi bản phát hành: sửa `releaseCode`, `releaseName` trong `app/build.gradle.kts`, thêm `fastlane/metadata/android/*/changelogs/<releaseCode>.txt`, commit, rồi tạo tag `vX.Y.Z` đúng với `releaseName`.
2. Fork https://gitlab.com/fdroid/fdroiddata, chép `fdroid/com.mykara.app.yml` vào `metadata/`, mở merge request. Có thể thử dựng trước bằng `fdroid lint com.mykara.app` và `fdroid build -v -l com.mykara.app`.
3. Trả lời góp ý của người duyệt (thường về mô tả, giấy phép phông chữ, bản dựng lại được).

## Điểm có thể bị hỏi

- **Chữ ký:** F-Droid tự ký bằng khóa của họ. File `app/debug.keystore` chỉ dùng cho bản dựng thử trên GitHub (khóa công khai, mật khẩu `android`), không được dùng để phát hành ở nơi khác. APK ở GitHub Releases và APK ở F-Droid vì thế KHÔNG cài đè lên nhau được: phải gỡ rồi cài lại (mất dự án nếu chưa xuất/sao lưu).
- **Gradle:** repo chưa có `gradlew`; `gradle/wrapper/gradle-wrapper.properties` chỉ rõ Gradle 8.7 (AGP 8.5.2 cần ≥ 8.7). F-Droid tự dùng bản Gradle phù hợp. Nếu họ yêu cầu wrapper, chạy `gradle wrapper --gradle-version 8.7` trên máy có mạng rồi commit `gradlew`, `gradlew.bat`, `gradle-wrapper.jar`.
- **Dựng lại được (reproducible):** chưa kiểm tra. Có thể thêm sau, không bắt buộc để vào F-Droid.
- **Dữ liệu bên ngoài:** `app/src/main/assets/` (phông chữ, ảnh QR) và `data/` (thư viện từ khóa → hình) đều nằm trong repo; ảnh QR ủng hộ là của chủ dự án.
- **Link ủng hộ:** màn Ủng hộ hiện chỉ có ảnh QR chuyển khoản (không cố định số tiền), chưa có link. F-Droid cho phép link `Donate:`/`Liberapay` trong metadata nếu bạn có.
