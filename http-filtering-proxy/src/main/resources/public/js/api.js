const Api = {
    async request(method, url, body) {
        const res = await fetch(url, {
            method,
            headers: { "Content-Type": "application/json" },
            body: body ? JSON.stringify(body) : undefined,
        });
        if (!res.ok) {
            throw new Error(`${method} ${url} -> ${res.status}`);
        }
        return res.status === 204 ? null : res.json();
    },
    get: (url) => Api.request("GET", url),
    post: (url, body) => Api.request("POST", url, body),
    put: (url, body) => Api.request("PUT", url, body),
    del: (url) => Api.request("DELETE", url),
};