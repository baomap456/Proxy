(function () {
    const NAV = [
        { id: "dashboard", label: "Dashboard", href: "/index.html", icon: "📊" },
        { id: "policies", label: "Policy Rules", href: "/policies.html", icon: "🛡️" },
        { id: "logs", label: "Access Logs", href: "/logs.html", icon: "📜" },
    ];

    document.addEventListener("DOMContentLoaded", () => {
        const page = document.body.dataset.page;
        const title = document.body.dataset.title || "";

        // Gom nội dung gốc của trang vào <div class="content">
        const content = document.createElement("div");
        content.className = "content";
        while (document.body.firstChild) {
            content.appendChild(document.body.firstChild);
        }

        const navHtml = NAV.map(
            (n) => `
      <a href="${n.href}" class="${n.id === page ? "active" : ""}">
        ${n.icon} <span class="text">${n.label}</span>
      </a>`
        ).join("");

        const app = document.createElement("div");
        app.className = "app";
        app.innerHTML = `
      <aside class="sidebar">
        <div class="brand">🔒 Proxy Admin</div>
        <nav>${navHtml}</nav>
      </aside>
      <div class="main">
        <header class="header">
          <h1>${title}</h1>
          <span class="badge">Proxy port: 8888</span>
        </header>
      </div>`;

        app.querySelector(".main").appendChild(content);
        document.body.appendChild(app);
    });
})();