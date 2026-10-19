const Api = {
    async request(method, url, body) {
        const res = await fetch(url, {
            method,
            headers: { "Content-Type": "application/json" },
            body: body ? JSON.stringify(body) : undefined,
        });
        if (!res.ok) {
            const err = new Error(`${method} ${url} -> ${res.status}`);
            err.status = res.status;
            throw err;
        }
        return res.status === 204 ? null : res.json();
    },
    get: (url) => Api.request("GET", url),
    post: (url, body) => Api.request("POST", url, body),
    put: (url, body) => Api.request("PUT", url, body),
    del: (url) => Api.request("DELETE", url),
};

// Escape dữ liệu trước khi đưa vào innerHTML (chống XSS)
function escapeHtml(s) {
    return String(s ?? "").replace(/[&<>"']/g, (c) => ({
        "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
    }[c]));
}

function validateRuleValue(type, value) {
    if (type === "IP") {
        const m = value.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?:\/(\d{1,2}))?$/);
        if (!m) return "IP phải có dạng 10.0.0.5 hoặc 10.0.0.0/24";
        if (m.slice(1, 5).some((n) => Number(n) > 255)) return "Mỗi phần của IP phải từ 0 đến 255";
        if (m[5] !== undefined && Number(m[5]) > 32) return "Độ dài mạng (CIDR) phải từ 0 đến 32";
    } else if (type === "DOMAIN") {
        if (!/^(\*\.)?([a-z0-9-]+\.)+[a-z0-9-]{2,}$/i.test(value)) {
            return "Domain không hợp lệ, vd: facebook.com hoặc *.gambling.com (không kèm http:// hay đường dẫn)";
        }
    }
    return null;
}