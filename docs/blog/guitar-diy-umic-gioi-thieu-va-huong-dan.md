# Thông tin dán vào CMS (không thuộc nội dung bài)

- **Tiêu đề:** Guitar DIY: Tự làm micro WiFi cho video guitar với ESP32-S3
- **Slug:** guitar-diy-micro-wifi-esp32-s3-umic
- **Tóm tắt (danh sách + SEO):** Hướng dẫn tự làm uMIC: micro không dây ESP32-S3 + INMP441 và app Android quay video guitar có tiếng thu từ mic ngoài, tự khớp tiếng với hình. Linh kiện dưới 150 nghìn, mã nguồn mở.
- **Meta title:** Tự làm micro WiFi quay video guitar với ESP32-S3 – Guitar DIY
- **Meta description:** uMIC là micro WiFi tự làm từ ESP32-S3 + INMP441, đi kèm app Android quay video và tự động khớp tiếng. Hướng dẫn đầy đủ: linh kiện, đấu dây, nạp firmware, cài app, mẹo đặt mic. Mã nguồn mở.
- **Ảnh đại diện:** `umic-mounted-on-guitar.jpg`
- **Thẻ:** ESP32, ESP32-S3, INMP441, I2S, Android, Guitar, DIY, Micro không dây, Mã nguồn mở
- **Chuyên mục gợi ý:** Dự án / ESP32

Ảnh dùng trong bài nằm ở `docs/images/` của repo (7 file). Vị trí chèn được đánh dấu bằng dòng `![...](tên-file)` kèm chú thích in nghiêng ngay dưới.

---

# Guitar DIY: Tự làm micro WiFi cho video guitar với ESP32-S3

Bạn quay clip chơi guitar bằng điện thoại, xem lại thì hình đẹp mà tiếng thì nhỏ, xa, lẫn tiếng quạt và tiếng đường. Mua micro rời thì lại phải thu riêng rồi ngồi ghép tiếng vào hình trên máy tính, canh từng phần trăm giây. Mình đã từng bực chuyện này đủ lâu để quyết định tự làm một món đồ giải quyết nó, và đó là lý do **Guitar DIY** ra đời: một dự án dành cho người mê guitar và thích tự làm đồ điện tử, muốn có một món đồ của riêng mình để chơi cùng cây đàn.

Món đầu tiên của dự án tên là **uMIC**: một chiếc micro nhỏ bằng bao diêm, gắn lên mặt đàn, gửi âm thanh qua WiFi thẳng vào điện thoại. App trên điện thoại vừa quay video vừa nhận tiếng từ mic, bấm dừng là có ngay một file MP4 với tiếng đàn sạch, khớp hình, không cần máy tính.

![Hộp mic uMIC gắn trên mặt đàn](umic-mounted-on-guitar.jpg)
*uMIC gắn lên mặt đàn, ngay phía trên cần. Hộp gỗ tự làm, cạnh khoảng 3 cm.*

Toàn bộ mã nguồn (firmware + app) mở trên GitHub, và có sẵn file để nạp/cài không cần biết lập trình: **https://github.com/nguyminhdc78-del/guitar-diy**

## uMIC làm được gì

- **Thu âm 48 kHz / 16-bit** từ micro MEMS INMP441, gửi qua WiFi tới điện thoại, không cần router: chính con ESP32 phát WiFi.
- **Quay trong app:** app tự mở camera, quay hình bằng điện thoại và thu tiếng từ mic cùng lúc. Bấm dừng, app tự ghép tiếng vào hình và **tự canh khớp** (sai số vài chục mili-giây).
- **Quay bằng Camera máy:** nếu thích dùng app Camera quen thuộc của Samsung/Xiaomi, uMIC chỉ ghi âm chạy nền; quay xong mở phần "Ghép" là app tự tìm file ghi âm đúng giờ và tự khớp bằng tiếng vỗ tay.
- **Nút bấm trên mic:** đặt điện thoại xa rồi bấm nút trên mic để bắt đầu/dừng quay, khỏi chạy tới chạy lui.
- **Đèn LED báo trạng thái**, và mic tự kiểm tra dây: đứt dây, hàn lỏng là LED nháy tím và app hiện cảnh báo.
- Zoom, chạm lấy nét, đổi camera trước/sau ngay trong app.

![Màn hình chính của app uMIC](app-home.jpg)
*Màn hình chính: hai chế độ quay, mục ghép thủ công, và danh sách file gần đây.*

## Nó hoạt động ra sao (giải thích ngắn)

Nếu bạn mới làm quen với điện tử, chỉ cần nắm đường đi của âm thanh:

1. **INMP441** là micro số: thay vì xuất tín hiệu điện yếu như micro thường, nó xuất thẳng dữ liệu số qua giao tiếp I2S (3 dây: clock, chọn kênh, dữ liệu). Không cần mạch khuếch đại, không cần ADC, ít nhiễu.
2. **ESP32-S3** đọc dữ liệu đó 48.000 lần mỗi giây, gom thành từng gói 20 ms và gửi qua WiFi. ESP32 tự phát mạng WiFi tên `uMIC`, điện thoại chỉ việc kết nối.
3. **App Android** nhận từng gói, ghi thành file WAV vào thư mục `Music/uMIC`. Khi quay video, app ghi nhớ chính xác thời điểm video bắt đầu và thời điểm gói âm thanh đầu tiên tới.
4. **Ghép:** app lấy file video, thay phần tiếng của điện thoại bằng tiếng từ mic (nén AAC), giữ nguyên hình. Để khớp chính xác, app so sánh "đường bao" âm lượng của hai bản thu (tiếng điện thoại và tiếng mic) để tìm độ lệch, kết hợp với đồng hồ hệ thống. Vì thế khi quay bằng Camera máy, bạn chỉ cần **vỗ tay một cái** ở đầu clip là app có mốc để khớp.

Tiếng đàn không đi qua internet, không qua cloud, mọi thứ chạy giữa mic và điện thoại trong cùng phòng.

## Linh kiện

Giá tham khảo ở các shop linh kiện trong nước (9/2026), tổng dưới 150 nghìn:

| Linh kiện | Ghi chú | Giá tham khảo |
|---|---|---|
| ESP32-S3 SuperMini (chip ESP32-S3FH4R2, 4 MB flash, 2 MB PSRAM) | Board nào ESP32-S3 có PSRAM cũng được; SuperMini nhỏ nhất, có sẵn LED RGB | 60–100 k |
| Micro INMP441 (module I2S) | Loại module 6 chân phổ biến | 25–40 k |
| Tụ hóa 100–470 µF, ≥ 6.3 V | **Bắt buộc**, giải thích ở phần đấu dây | 1–2 k |
| Nút nhấn 4 chân (tact switch) | Tùy chọn, để điều khiển từ xa | 1 k |
| Dây đồng nhỏ, cáp USB-C có data | Dây mic càng ngắn càng tốt | có sẵn |
| Vỏ hộp | Mình làm hộp gỗ; in 3D, hộp nhựa nhỏ, hay nắp chai đều được | tùy |

Dụng cụ: mỏ hàn, thiếc, kìm cắt. Có đồng hồ vạn năng thì tốt để kiểm tra chập, không có cũng làm được.

Điện thoại: Android 10 trở lên. Mình test trên Samsung S21 FE.

## Đấu dây

INMP441 nối với ESP32-S3 bằng 6 dây, dây nào ra chân nào như bảng:

| Chân INMP441 | Nối tới ESP32-S3 | Ghi chú |
|---|---|---|
| VDD | 3V3 | **Không** nối 5V, mic chỉ chịu tối đa 3.6 V |
| GND | GND | |
| L/R | GND | Chọn kênh trái. Để hở là mic im lặng |
| SD | GPIO 5 | Dữ liệu âm thanh |
| WS | GPIO 4 | Chọn kênh |
| SCK | GPIO 3 | Xung clock |

Nút bấm (tùy chọn): một chân vào **GPIO 7**, chân kia vào **GND**. Không cần trở kéo, firmware đã bật trở kéo trong chip.

**Tụ chống nhiễu – đừng bỏ qua.** ESP32 khi phát WiFi hút dòng theo từng xung (300–400 mA trong vài trăm micro-giây). Xung này làm nguồn 3.3 V của mic nhấp nhô, và mic nghe thấy nó: bản thu bị ù 50 Hz kèm tiếng "bụp bụp" đều đặn. Cách chữa rẻ nhất là hàn một tụ hóa **100–470 µF** ngay tại hai chân **VDD và GND của mic**, chân tụ cắt ngắn dưới 5 mm. Chân dài của tụ (+) vào VDD, chân có vạch trắng (−) vào GND; hàn ngược là tụ nóng và phồng. Mình đã mất nguyên một buổi đi tìm "tiếng radio" trong bản thu trước khi nhận ra thủ phạm là thiếu con tụ 2 nghìn đồng này.

![Bên trong hộp: tụ lọc nguồn và nút bấm](umic-box-inside-capacitor-button.jpg)
*Bên trong hộp: tụ hóa 470 µF (trụ đen) hàn sát mic, nút nhấn ở góc, tụ gốm màu cam hàn song song.*

Vài lưu ý khi hàn:

- Dây mic dài **2–5 cm** là đẹp. Dây dài vừa nhiễu vừa dễ đứt ngầm.
- Mối hàn phải bóng và loang đều. Mối xỉn, sần là mối nguội, sau vài ngày sẽ chập chờn. Ở dự án này, mọi lỗi "tự nhiên hôm nay bị rè" của mình đều truy ra một mối hàn nguội.
- Hàn mic nhanh tay (2–3 giây mỗi mối), INMP441 không ưa nóng lâu.
- Trước khi cắm điện, kiểm tra 3V3 không chập với GND.

## Nạp firmware cho ESP32-S3

Có hai cách. Cách nhanh không cần cài môi trường lập trình:

**Cách 1 – nạp file có sẵn.** Vào mục *Releases* của repo, tải file `umic-firmware-v0.2.1-esp32s3-4mb-flash-at-0x0.bin`. Mở trang **https://espressif.github.io/esptool-js/** bằng Chrome/Edge, cắm ESP32 vào máy tính, bấm *Connect*, chọn cổng COM, thêm file vừa tải với địa chỉ **0x0**, bấm *Program*. Nếu máy không nhận cổng, giữ nút BOOT trên board rồi cắm USB.

Ai quen dòng lệnh:

```
pip install esptool
esptool.py --chip esp32s3 write_flash 0x0 umic-firmware-v0.2.1-esp32s3-4mb-flash-at-0x0.bin
```

**Cách 2 – biên dịch từ mã nguồn** (khi muốn sửa firmware, ví dụ đổi chân, đổi độ nhạy):

```
pip install platformio
git clone https://github.com/nguyminhdc78-del/guitar-diy
cd guitar-diy/firmware
pio run -e mic -t upload
```

Lần đầu PlatformIO sẽ tải bộ công cụ ESP32 (khoảng 1 GB), kiên nhẫn chút. Trên Windows chạy trong PowerShell, không chạy trong Git Bash.

Nạp xong, rút cắm lại USB: LED trên board sáng **trắng mờ** lúc khởi động rồi **nháy xanh dương** chậm, nghĩa là mic đã phát WiFi và đang chờ điện thoại. Nếu LED **nháy tím** liên tục, mic không có tín hiệu: kiểm tra lại 6 dây, đặc biệt L/R và VDD.

Muốn kiểm tra mic trước khi làm app: mở *Serial Monitor* (115200 baud), firmware in một dòng thống kê mỗi 5 giây, và in `[mic] NO SIGNAL` kèm gợi ý nếu dây có vấn đề.

## Cài app Android

**Cách 1:** tải `umic-app-v0.2.1.apk` từ *Releases*, mở file trên điện thoại, cho phép cài từ nguồn không xác định. App xin 3 quyền: Camera, Micro (để có tiếng tham chiếu khi khớp) và Thông báo (để hiện tiến trình ghi khi tắt màn hình).

**Cách 2:** mở thư mục `android` bằng Android Studio, bấm Run. App viết bằng Kotlin, dùng CameraX, không có thư viện lạ.

Lần đầu bấm ghi, Android sẽ hỏi cho phép app kết nối WiFi `uMIC`. Chọn đồng ý một lần là xong, những lần sau app tự nối. Mật khẩu WiFi mặc định là `umic12345` (chỉ cần khi bạn muốn nối thủ công trong Cài đặt).

## Cách dùng

### Chế độ 1: Quay trong app (khuyên dùng)

1. Bật mic (cắm USB). Mở app, chọn **Quay trong app**.
2. Đợi nhãn góc trên chuyển thành **"Đã nối micro"** (2–5 giây).
3. Bấm nút **REC** trên màn hình, hoặc **bấm nút trên mic** nếu điện thoại để xa. LED trên mic chuyển **đỏ** khi đang quay.
4. Chơi đàn. Bấm REC hoặc nút trên mic lần nữa để dừng.
5. App hiện tiến trình ghép vài giây, rồi thông báo "Đã lưu". Video nằm trong `Movies/uMIC`, mở được bằng Gallery như video thường.

![Màn hình quay trong app](app-record-in-app.jpg)
*Màn hình quay: nhãn trạng thái mic ở góc trên, nút REC, nút zoom "1×" (chụm hai ngón hoặc bấm để đổi 0.5×/1×/2×/3×, chạm vào hình để lấy nét).*

Nếu app không chắc về độ khớp (ví dụ đoạn thu quá ngắn hoặc quá im lặng), nó giữ thêm file video gốc `-raw.mp4` để bạn ghép tay ở mục Ghép.

### Chế độ 2: Quay bằng Camera máy

Dành cho ai thích chất lượng hình của app Camera hãng (HDR, chống rung, 4K).

1. Mở app, chọn **Quay bằng Camera máy**, bấm **Ghi âm**. App ghi âm chạy nền, có thông báo hiện thời gian.
2. Chuyển sang app Camera, **vỗ tay một cái thật gọn** trước ống kính, rồi quay như bình thường.
3. Quay xong, quay lại uMIC bấm **Dừng**, rồi vào **Ghép**: app tự chọn video mới nhất và file ghi âm cùng khoảng giờ, tự tìm tiếng vỗ tay để khớp. Nghe thử, thấy khớp thì bấm Ghép.

![Màn hình ghi âm nền cho chế độ Camera máy](app-record-external.jpg)
*Chế độ Camera máy: đồng hồ, mức âm, số khung mất, và hướng dẫn 4 bước ngay dưới nút.*

### LED trên mic nghĩa là gì

| LED | Ý nghĩa |
|---|---|
| Trắng mờ | Đang khởi động |
| Xanh dương nháy chậm | Sẵn sàng, chờ điện thoại |
| Xanh lá | Điện thoại đã nối và đang nhận âm thanh |
| Đỏ | Đang quay (app xác nhận) |
| Cam nháy 1 cái | Đã nhận nút bấm |
| Trắng nháy | Mất 1 gói dữ liệu (WiFi kém) |
| Tím nháy nhanh | Mic không có tín hiệu, kiểm tra dây |

## Đặt mic ở đâu cho tiếng hay

Đây là phần mình sai nhiều nhất, nên kể luôn để bạn khỏi lặp lại.

**Không bỏ mic vào trong thùng đàn.** Mình đã thử vì nghĩ trong thùng tiếng sẽ "đầy". Kết quả: tiếng cụt, đục, thiếu hết phần sáng, vì trong thùng chỉ có tiếng bass cộng hưởng còn tiếng dây thì bị chính mặt đàn chắn mất. Đo phổ thấy dải 1–4 kHz thấp hơn 10–13 dB so với đặt ngoài.

Chỗ tốt: **gắn lên mặt đàn phía trên cần** như ảnh đầu bài, hoặc kẹp ở cạnh đàn, **lỗ mic hướng về phía dây**, cách ngăn 12 khoảng 20–40 cm. INMP441 thu qua một lỗ nhỏ ở mặt sau module, nên khi làm hộp bạn khoan một lỗ 2–3 mm đúng vị trí đó và dán module áp sát mặt trong.

![Mặt trước hộp với lỗ thu âm](umic-box-mic-port.jpg)
*Mặt trước hộp chỉ có một lỗ nhỏ, đúng vị trí lỗ thu của INMP441 phía trong. Nút bấm lộ ở cạnh trên.*

Độ nhạy mặc định (`MIC_SHIFT_BITS=14`, tương đương +12 dB) hợp với guitar acoustic đệm hát bình thường. Chơi mạnh mà thấy méo thì tăng lên 15–16; hát nhỏ mà tiếng nhỏ thì hạ xuống 12–13 (sửa trong `platformio.ini`, nạp lại).

## Những lỗi mình đã gặp

Ghi lại vì chắc bạn cũng sẽ gặp một vài cái:

- **Board không boot, báo "partition exceeds flash size"**: mua nhầm loại chip. ESP32-S3 SuperMini dùng chip FH4R2 chỉ có 4 MB flash, trong khi nhiều hướng dẫn mặc định N16R8. Firmware trong repo đã cấu hình cho 4 MB; nếu board bạn là 16 MB, đổi 3 dòng trong `platformio.ini` (có ghi chú sẵn).
- **Tiếng rè như radio, có lúc mất hẳn**: dây mic hàn lỏng. Từ đó firmware có thêm chức năng tự kiểm tra và LED tím.
- **Ù 50 Hz + "bụp bụp"**: thiếu tụ lọc nguồn. Đã nói ở trên, con tụ 2 nghìn.
- **Tiếng trong video trễ 43 ms** so với file WAV: do bộ mã hóa AAC của Android chèn thêm 2048 mẫu ở đầu. App đã bù, nhưng nếu bạn tự viết phần ghép thì nhớ chi tiết này.
- **Điện thoại tự rớt WiFi uMIC** vì mạng không có internet: app dùng API kết nối chuyên dụng của Android nên không bị, nhưng nếu nối thủ công trong Cài đặt thì nhớ tắt "Tự chuyển sang mạng khác".

## Kết

Toàn bộ dự án tốn khoảng một buổi tối hàn và vài buổi tối cho phần mềm. Với 150 nghìn tiền linh kiện và một cái hộp tự đóng, mình có một chiếc micro dùng hằng ngày, tiếng sạch hơn hẳn micro điện thoại và không phải ngồi canh ghép nữa.

Mã nguồn, file nạp sẵn và tài liệu kỹ thuật (giao thức, kiến trúc, thuật toán khớp) ở **https://github.com/nguyminhdc78-del/guitar-diy**. Bạn làm theo mà kẹt ở đâu, hoặc muốn góp ý, cứ mở Issue trên GitHub hoặc bình luận dưới bài.

Dự định tiếp theo của Guitar DIY: chế độ **USB mic** để uMIC dùng được với mọi app ghi âm như một micro USB thường, **pin sạc** cho gọn (board SuperMini có sẵn chân B+/B−), và EQ tùy chỉnh trong app. Ai hứng thú thì theo dõi repo nhé.
