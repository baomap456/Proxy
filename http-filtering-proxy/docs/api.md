# API Reference (Backend, cổng 8080)

Quy ước: JSON, tên trường camelCase. `priority` số nhỏ được xét trước. `decision`/`action` chỉ nhận `BLOCK` hoặc `ALLOW`.
Lỗi: 400 (dữ liệu sai), 404 (không tìm thấy).

## Policy

### GET /api/policies/active
Rule đang bật (`enabled=true`), sắp xếp `priority` tăng dần. Proxy gọi mỗi 5 giây.
200: `[{"id":1,"type":"DOMAIN","value":"facebook.com","action":"BLOCK","priority":10,"enabled":true,"note":"MXH"}]`

### GET /api/policies
Tất cả rule (kể cả đã tắt), sắp xếp `priority`, `id`.

### GET /api/policies/{id}
200: một rule. 404 nếu không có.

### POST /api/policies
Body: `{"type":"DOMAIN","value":"facebook.com","action":"BLOCK","priority":10,"enabled":true,"note":""}`
201: rule vừa tạo (có `id`). 400 nếu `type`/`action` sai hoặc `value` rỗng.

### PUT /api/policies/{id}
Body như POST (thay toàn bộ, dùng cả để bật/tắt qua `enabled`). 200: rule sau cập nhật. 404 nếu không có.

### DELETE /api/policies/{id}
204 nếu xóa được. 404 nếu không có.

## Logs

### POST /api/logs/batch
Body: mảng LogEntry
`[{"timestamp":"2026-10-09T14:30:00","clientIp":"127.0.0.1","method":"GET","host":"google.com","path":"/","decision":"ALLOW","ruleId":null,"statusCode":200}]`
200: `{"inserted":1}`. 400 nếu mảng rỗng hoặc `decision` sai.

### GET /api/logs?page=0&size=20&host=&decision=
`page` bắt đầu từ 0, `size` tối đa 100, `host` tìm gần đúng, `decision` BLOCK|ALLOW. Mới nhất trước.
200: `{"content":[LogEntry...],"page":0,"size":20,"totalElements":134,"totalPages":7}`

### GET /api/stats/summary
200: `{"totalRequests":500,"blocked":120,"blockRate":24.0,"topDomains":[{"host":"google.com","count":80}]}`
`blockRate` là phần trăm, làm tròn 1 chữ số.