# Kế hoạch chi tiết đồ án: HTTP Proxy Server với Content Filtering & Policy Rule Engine (Java)
---

## 1. Mục tiêu và phạm vi đề tài

### Mục tiêu
Xây dựng một HTTP Proxy Server bằng ngôn ngữ Java với các khả năng cốt lõi:
* Lắng nghe và chuyển tiếp (forward) request HTTP giữa client (trình duyệt) và web server đích.
* **Động cơ phân xử chính sách (Policy Rule Engine):** Thực thi các hành vi `ALLOW`, `BLOCK`, `ISOLATE` theo nguyên tắc duyệt trên-xuống (First-Match-Wins), có độ ưu tiên cao nhất trong toàn bộ hệ thống.
* **Lọc danh sách đen (Blacklist URL):** Chặn truy cập dựa trên danh sách URL/Domain đối với các request không khớp Rule Engine.
* **Lọc nội dung (Content Filtering):** Quét và chặn các trang trả về có chứa từ khóa cấm trong body HTML (`text/html`).
* **Hệ thống ghi log phân cấp (Logging):** Hỗ trợ mức ghi vết tiêu chuẩn (`Standard`) và chi tiết chuyên sâu (`Detailed`).

### Phạm vi giới hạn (Scope Limitations)
* **Xử lý giao thức:** Tập trung vào HTTP thuần (HTTP/1.1). Không triển khai Man-In-The-Middle (MITM) để giải mã HTTPS; với HTTPS chỉ hỗ trợ tạo đường hầm TCP thô (HTTP CONNECT method) và lọc theo Host/Domain.
* **Hiệu năng:** Hệ thống hướng đến mục tiêu đồ án học thuật, tập trung vào độ chính xác, kiến trúc module hóa và khả năng xử lý đồng thời qua Thread Pool; không tối ưu hóa chuyên sâu ở mức production.

---

## 2. Kiến trúc hệ thống & Luồng xử lý

```text
Browser (Client) ───[ HTTP Request ]───► [ Proxy Server : 8080 ]
                                                  │
                                                  ▼
                                         ClientHandler (Worker)
                                                  │
                                                  ▼
                                          HttpRequestParser
                                                  │
                                                  ▼
                                       ┌──────────────────────┐
                                       │  RuleEngine.check()  │ (First-Match-Wins)
                                       └──────────┬───────────┘
                                                  │
         ┌───────────────────┬────────────────────┼─────────────────────────┐
         │ ALLOW             │ ISOLATE            │ BLOCK                   │ NO_MATCH
         ▼                   ▼                    ▼                         ▼
   Forward Server      Forward Server       Trả trang 403 ngay      ┌───────────────────────┐
   Bỏ qua Filter       Bỏ qua Filter        Log BLOCKED             │ BlacklistManager.check│
   Log mức INFO        Log mức DETAILED     (Dừng xử lý)            └───────────┬───────────┘
         │                   │                                                  │
         │                   │                                     ┌────────────┴────────────┐
         │                   │                                     │ Match                   │ Không Match
         │                   │                                     ▼                         ▼
         │                   │                               Trả trang 403             Forward Server
         │                   │                               Log BLACKLIST                   │
         │                   │                               (Dừng xử lý)                    ▼
         │                   │                                                      HttpResponseParser
         │                   │                                                               │
         │                   │                                                               ▼
         │                   │                                                    ┌────────────────────┐
         │                   │                                                    │KeywordManager.check│
         │                   │                                                    └─────────┬──────────┘
         │                   │                                                              │
         │                   │                                                  ┌───────────┴───────────┐
         │                   │                                                  │ Match                 │ Không Match
         │                   │                                                  ▼                       ▼
         │                   │                                            Trả trang 403           Trả Response gốc
         │                   │                                            Log KEYWORD             Log ALLOWED
         ▼                   ▼                                                  │                       │
   Trả Response gốc    Trả Response gốc                                         │                       │
         │                   │                                                  │                       │
         └───────────────────┴─────────────────┬────────────────────────────────┴───────────────────────┘
                                               ▼
                                      Logger (Ghi log file)
```

### Luồng xử lý chi tiết 1 Request
1. **Kết nối:** `ProxyServer` accept kết nối TCP từ client và chuyển Socket sang một Thread trong Thread Pool (`ClientHandler`).
2. **Phân tích Request:** `HttpRequestParser` đọc raw bytes, parse thành đối tượng Request (Method, URI/Path, Host, Port, Headers, Body).
3. **Phân xử Rule Engine:** Chạy `RuleEngine.check(url)` duyệt từ trên xuống:
   * **`ALLOW`:** Bỏ qua Blacklist & Keyword Filter -> Forward ra server đích -> Trả response về client -> Log mức `Standard (INFO)`.
   * **`ISOLATE`:** Bỏ qua Blacklist & Keyword Filter -> Forward ra server đích -> Trả response về client -> Log mức `Detailed (ISOLATE)`.
   * **`BLOCK`:** Dừng xử lý ngay lập tức -> Tạo HTTP 403 Forbidden qua `BlockPageGenerator` -> Trả về client -> Log mức `Standard (BLOCKED)`.
4. **Luồng Fallback (khi `NO_MATCH`):**
   * Gọi `BlacklistManager.check(url)`. Nếu trùng khớp -> Trả về HTTP 403, log `BLACKLIST`, đóng kết nối.
   * Nếu hợp lệ -> Tạo Socket forward request đến server đích, nhận response byte stream.
   * `HttpResponseParser` phân tách Status code, Headers và Content Body (xử lý `Content-Length` và `Transfer-Encoding: chunked`).
   * Nếu Header chứa `Content-Type: text/html` -> Đưa body vào `KeywordManager.check(body)`.
   * Nếu phát hiện từ khóa cấm -> Thay thế response bằng trang chặn HTTP 403, log `KEYWORD`.
   * Nếu không vi phạm -> Trả response nguyên vẹn về client, log `ALLOWED`.

---

## 3. Quy tắc cốt lõi của Policy Rule Engine

### Định nghĩa các Action
| Action | Hành vi chuyển tiếp | Bỏ qua Filter khác? | Mức Log | Mục đích thực tế |
| :--- | :--- | :---: | :---: | :--- |
| **`ALLOW`** | Forward bình thường | **Có** (Bỏ Blacklist & Keyword) | Standard | Miễn trừ trang nội bộ, đối tác tin cậy |
| **`BLOCK`** | Chặn ngay, trả về 403 | **N/A** (Dừng xử lý) | Standard | Ngăn chặn tức thì các domain nguy hại |
| **`ISOLATE`** | Forward bình thường | **Có** (Bỏ Blacklist & Keyword) | Detailed | Giám sát nâng cao các dịch vụ nhạy cảm |

### Nguyên tắc kỹ thuật khi lập trình
* **Thứ tự First-Match-Wins:** Duyệt tuần tự danh sách rules từ trên xuống dưới. Gặp rule đầu tiên khớp với pattern thì thực thi action của rule đó ngay lập tức và dừng duyệt.
* **Cấu trúc dữ liệu:** Bắt buộc sử dụng danh sách có thứ tự `java.util.List` (hoặc `ArrayList`). Tuyệt đối **không** dùng `HashSet`/`HashMap` để lưu trữ rules vì các cấu trúc bảng băm không đảm bảo thứ tự khai báo từ file.
* **Quyền ưu tiên:** Rule Engine có quyền ghi đè toàn bộ Blacklist URL và Keyword Filter.


## 4. Đặc tả cấu trúc Module
| Module / Class | Trách nhiệm chính | Dữ liệu đầu vào (Input) | Dữ liệu đầu ra (Output) |
| :--- | :--- | :--- | :--- |
| **`ProxyServer`** | Main entry: Lắng nghe `ServerSocket`, quản lý vòng đời `ExecutorService` (Thread Pool). | File config (Port, Pool size) | Lắng nghe Socket, đẩy task cho thread pool |
| **`ClientHandler`** | Điều phối toàn bộ vòng đời của một phiên HTTP client từ lúc connect đến khi close. | `Socket` client | Dữ liệu trả về cho client socket |
| **`HttpRequestParser`** | Parse HTTP raw bytes thành đối tượng có cấu trúc. | Client `InputStream` | Đối tượng `HttpRequest` (Method, URI, Host, Headers,...) |
| **`HttpResponseParser`** | Parse HTTP response, giải mã chunked encoding, đọc body theo Content-Length. | Remote Server `InputStream` | Đối tượng `HttpResponse` (Status, Headers, Body bytes) |
| **`RuleModel`** | Lưu trữ cấu trúc của 1 Rule: `pattern`, `action` (Enum), `note`. | Dữ liệu cấu hình từng dòng | Đối tượng `RuleModel` |
| **`RuleManager`** | Đọc file rules, lưu giữ danh sách `List<RuleModel>`, hỗ trợ so khớp wildcard (`*.domain.com`). | File `rules.json` | `List<RuleModel>`, hàm `matchRule(url)` |
| **`RuleEngine`** | Duyệt tuần tự danh sách rule, quyết định action theo chiến lược First-Match-Wins. | URL/Domain, `List<RuleModel>` | `RuleAction` (ALLOW / BLOCK / ISOLATE / NO_MATCH) |
| **`BlacklistManager`** | Quản lý danh sách URL/Domain cấm (dùng `HashSet` để tra cứu O(1)). | File `blacklist.txt` | `boolean` (isBlacklisted) |
| **`KeywordManager`** | Lưu danh sách từ khóa cấm, quét body HTML không phân biệt hoa thường (Case-insensitive). | File `keywords.txt`, String body | `boolean` (containsForbiddenKeyword) |
| **`BlockPageGenerator`** | Tạo mã phản hồi chuẩn HTTP/1.1 403 Forbidden kèm giao diện thông báo lỗi HTML. | Lý do chặn (Rule/Blacklist/Keyword) | Mảng bytes HTTP response 403 hoàn chỉnh |
| **`ConfigLoader`** | Load các thuộc tính hệ thống: port, pool size, đường dẫn file rule, switch bật/tắt filter. | File `config.properties` | Đối tượng cấu hình dùng chung (Singleton) |
| **`Logger`** | Ghi log hệ thống và transaction mạng hỗ trợ 2 cấp độ Standard và Detailed. | Đối tượng LogEvent / Transaction | Ghi log ra file log chuyên biệt và console |

## 5. Đặc tả hệ thống Logging
| Cấp độ Log | Áp dụng cho các trường hợp | Thông tin bắt buộc ghi nhận |
| :--- | :--- | :--- |
| **Standard** | `ALLOW`, request hợp lệ, bị chặn bởi `Blacklist` hoặc `Keyword`, hoặc `BLOCK` do Rule. | Timestamp, Client IP, Target URL, Action (`ALLOW`/`BLOCK`), Quyết định bởi (`RULE`/`BLACKLIST`/`KEYWORD`). |
| **Detailed** | Áp dụng riêng cho các truy cập khớp rule `ISOLATE`. | Toàn bộ thông tin Standard + HTTP Method, User-Agent, Response Status Code, Content-Type, Content-Length, Thời gian xử lý request (ms), Tên Pattern khớp. |

## 6. Danh mục Checklist chức năng

### Nhóm chức năng cốt lõi (Core Functions)
- [ ] Server lắng nghe TCP socket trên port cấu hình được qua `config.properties`.
- [ ] Đa luồng: Xử lý nhiều client đồng thời thông qua Java Thread Pool (`ExecutorService`).
- [ ] Parse chuẩn HTTP Request: GET tối thiểu, parse đúng `Host` header và port đích.
- [ ] Forward HTTP request chuẩn xác đến remote server và đọc response trả về client.
- [ ] Xử lý đúng HTTP response body: parse chính xác cả `Content-Length` và `Transfer-Encoding: chunked`.
- [ ] **Rule Engine:** Triển khai `RuleModel` (ALLOW, BLOCK, ISOLATE) và `RuleManager` duyệt theo thứ tự `List` (First-Match-Wins).
- [ ] Rule Engine được đặt tại vị trí đầu tiên của luồng kiểm duyệt; nếu khớp thì bỏ qua Blacklist và Keyword check.
- [ ] **Blacklist Filter:** Chặn các domain có trong danh sách đen khi không khớp Rule Engine.
- [ ] **Keyword Filter:** Quét nội dung phản hồi `text/html` và chặn nếu chứa từ khóa cấm.
- [ ] Trả về mã lỗi HTTP 403 Forbidden kèm giao diện HTML nêu rõ lý do bị chặn.
- [ ] Module Logger hỗ trợ 2 chế độ `Standard` và `Detailed` theo đúng thiết kế action.

### Nhóm chức năng nâng cao (Advanced Features)
- [ ] Hỗ trợ so khớp Wildcard trong cả Rule Engine và Blacklist (ví dụ: `*.domain.com`).
- [ ] Hỗ trợ phương thức `CONNECT` cho HTTPS tunneling (chỉ lọc theo domain ở giai đoạn bắt tay TCP, không quét nội dung).
- [ ] Cơ chế xử lý Timeout (Connection timeout, Read timeout) khi server đích chết hoặc phản hồi chậm $\rightarrow$ Trả về mã 504 Gateway Timeout / 502 Bad Gateway.
- [ ] Đọc cấu hình từ file ngoài, hỗ trợ cơ chế reload rule mà không cần dừng server.

## 7. Kế hoạch phân công công việc
| Thành viên | Module phụ trách | Chi tiết công việc triển khai |
| :--- | :--- | :--- |
| **TV1 — Networking Core** | `ProxyServer`, `ClientHandler` | • Thiết lập `ServerSocket`, Thread Pool, quản lý kết nối TCP.<br>• Viết luồng rẽ nhánh điều phối tại `ClientHandler`: kết nối `RuleEngine`, `Blacklist`, forward socket, xử lý Exception mạng/timeout. |
| **TV2 — HTTP Protocol** | `HttpRequestParser`, `HttpResponseParser` | • Parse stream HTTP request & response chuẩn HTTP/1.1.<br>• Giải mã `Transfer-Encoding: chunked`, bóc tách `Content-Length`, `Content-Type`.<br>• Đảm bảo cung cấp đủ trường thông tin (status, size, content-type) cho Logger Detailed. |
| **TV3 — Filtering & Policy** | `RuleModel`, `RuleManager`, `RuleEngine`, `BlacklistManager`, `KeywordManager`, `BlockPageGenerator` | • Code `RuleModel` và `RuleManager` (giữ thứ tự List từ file cấu hình).<br>• Lập trình `RuleEngine.check()` theo First-Match-Wins, logic so khớp Wildcard.<br>• Cài đặt tra cứu Blacklist ($O(1)$ với `HashSet`), quét Keyword trong HTML body, thiết kế template trang 403. |
| **TV4 — Support, System & QA** | `Logger`, `ConfigLoader`, Test Suite, Tài liệu | • Lập trình `ConfigLoader` và `Logger` (hỗ trợ phân định Standard vs Detailed).<br>• Chuẩn bị file dữ liệu mẫu (`rules.json`, `blacklist.txt`, `keywords.txt`).<br>• Xây dựng test scripts, chạy bảng kiểm thử 14 Test Cases, viết báo cáo kỹ thuật và slide thuyết trình. |

## 8. Bảng kịch bản kiểm thử toàn diện (Test Cases)
| STT | Kịch bản kiểm thử | Dữ liệu đầu vào (Input) | Kết quả mong đợi |
| :---: | :--- | :--- | :--- |
| **1** | Truy cập web bình thường | URL không khớp Rule, không nằm trong Blacklist, không chứa từ khóa cấm | Trang web hiển thị bình thường; Log ghi nhận mức `ALLOW (STANDARD)`. |
| **2** | Chặn qua Blacklist thông thường | URL nằm trong `blacklist.txt`, không khớp Rule nào | Trả về trang lỗi 403 Forbidden "Bị chặn bởi Blacklist"; không forward ra ngoài. |
| **3** | Chặn qua Keyword Filter | URL hợp lệ nhưng nội dung HTML chứa từ trong `keywords.txt` | Trả về trang lỗi 403 Forbidden "Bị chặn do nội dung chứa từ khóa cấm". |
| **4** | Client truy cập đồng thời | Gửi đồng thời 10 - 20 kết nối từ trình duyệt/script | Hệ thống xử lý mượt mà qua Thread Pool; không crash, không nghẽn luồng. |
| **5** | Server đích không phản hồi | Truy cập domain không tồn tại hoặc IP bị drop gói tin | Trả về mã lỗi 504/502 tương ứng, proxy đóng kết nối an toàn, không văng Exception. |
| **6** | Xử lý Transfer-Encoding: Chunked | Server đích trả response dạng chunked | Parser giải mã và ghép nối đầy đủ body trước khi đưa qua `KeywordManager`. |
| **7** | Rule ALLOW đứng trước Rule BLOCK (Cùng pattern) | File cấu hình: dòng 1 `ALLOW`, dòng 2 `BLOCK` cho cùng `*.example.com` | Áp dụng `ALLOW` cho client truy cập (First-Match-Wins); bỏ qua rule BLOCK phía sau. |
| **8** | Rule BLOCK đứng trước Rule ALLOW (Cùng pattern) | Đảo thứ tự: dòng 1 `BLOCK`, dòng 2 `ALLOW` cho cùng `*.example.com` | Áp dụng `BLOCK` ngay lập tức, trả về trang 403. |
| **9** | Rule ALLOW ghi đè Blacklist | `internal.company.com` vừa có Rule `ALLOW`, vừa có tên trong `blacklist.txt` | Vẫn cho phép truy cập bình thường (Rule Engine có quyền ưu tiên cao hơn Blacklist). |
| **10** | Kích hoạt chế độ giám sát ISOLATE | URL khớp với pattern có action `ISOLATE` | Trang web tải bình thường; File log xuất hiện bản ghi `[ISOLATE]` chứa đầy đủ Headers, IP, Size, Request Time. |
| **11** | Khớp rule ISOLATE nhưng body chứa từ khóa cấm | Response HTML của trang khớp `ISOLATE` có chứa từ khóa trong `keywords.txt` | Vẫn cho phép hiển thị (do Rule Engine đã match thì bỏ qua Keyword Check); log Detailed ghi nhận đầy đủ. |
| **12** | Request không khớp bất kỳ Rule nào | URL không nằm trong `rules.json` | Rơi về luồng thẩm định mặc định: kiểm tra qua `BlacklistManager` $\rightarrow$ `KeywordManager`. |
| **13** | Dữ liệu rác (Malformed HTTP) | Gửi chuỗi binary ngẫu nhiên hoặc raw text sai quy chuẩn HTTP | Parser bắt lỗi cú pháp, trả về 400 Bad Request hoặc đóng socket an toàn. |
| **14** | Xử lý Wildcard domain | Rule định nghĩa `*.trusted.com`, input request: `sub.api.trusted.com` | Khớp rule chính xác theo cơ chế wildcard matching. |

### Cấu trúc file cấu hình rules (`rules.json`)
```json
[
  {
    "pattern": "internal.company.com",
    "action": "ALLOW",
    "note": "Trang nội bộ doanh nghiệp - ưu tiên duyệt tuyệt đối"
  },
  {
    "pattern": "*.gambling-site.com",
    "action": "BLOCK",
    "note": "Chặn cờ bạc trực tuyến"
  },
  {
    "pattern": "*.social-media.com",
    "action": "ISOLATE",
    "note": "Giám sát lưu lượng truy cập mạng xã hội"
  },
  {
    "pattern": "*.bad-network.com",
    "action": "BLOCK",
    "note": "Chặn mạng độc hại"
  }
]

