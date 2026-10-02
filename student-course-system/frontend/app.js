const $ = (id) => document.getElementById(id);
const pool = new AmazonCognitoIdentity.CognitoUserPool({
  UserPoolId: CONFIG.userPoolId, ClientId: CONFIG.clientId
});
let idToken = null, isAdmin = false, pendingEmail = null;

const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) =>
  ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

function say(el, text, ok = false) {
  el.textContent = text;
  el.style.color = ok ? "#0F7B8A" : "#B3261E";
}
function showForm(id) {
  ["login-form", "signup-form", "confirm-form"].forEach((f) => ($(f).hidden = f !== id));
  $("tab-login").classList.toggle("active", id === "login-form");
  $("tab-signup").classList.toggle("active", id === "signup-form");
  say($("msg"), "");
}
$("tab-login").onclick = () => showForm("login-form");
$("tab-signup").onclick = () => showForm("signup-form");

// ---------- Auth ----------
$("btn-signup").onclick = () => {
  const email = $("s-email").value.trim();
  const attrs = [
    new AmazonCognitoIdentity.CognitoUserAttribute({ Name: "email", Value: email }),
    new AmazonCognitoIdentity.CognitoUserAttribute({ Name: "name", Value: $("s-name").value.trim() })
  ];
  pool.signUp(email, $("s-pass").value, attrs, null, (err) => {
    if (err) return say($("msg"), err.message);
    pendingEmail = email;
    showForm("confirm-form");
    say($("msg"), "Code sent. Check your inbox.", true);
  });
};

$("btn-confirm").onclick = () => {
  const user = new AmazonCognitoIdentity.CognitoUser({ Username: pendingEmail, Pool: pool });
  user.confirmRegistration($("c-code").value.trim(), true, (err) => {
    if (err) return say($("msg"), err.message);
    showForm("login-form");
    say($("msg"), "Account verified. You can log in now.", true);
  });
};

$("btn-login").onclick = () => {
  const email = $("l-email").value.trim();
  const user = new AmazonCognitoIdentity.CognitoUser({ Username: email, Pool: pool });
  const details = new AmazonCognitoIdentity.AuthenticationDetails({
    Username: email, Password: $("l-pass").value
  });
  user.authenticateUser(details, {
    onSuccess: (session) => startApp(session.getIdToken()),
    onFailure: (err) => {
      if (err.code === "UserNotConfirmedException") {
        pendingEmail = email; showForm("confirm-form");
        return say($("msg"), "Verify your email first. Enter the code we sent.");
      }
      say($("msg"), err.message);
    }
  });
};

function logout() {
  const u = pool.getCurrentUser();
  if (u) u.signOut();
  idToken = null;
  $("app").hidden = true; $("userbar").hidden = true; $("auth").hidden = false;
  showForm("login-form");
}
$("logout").onclick = logout;

function startApp(token) {
  idToken = token.getJwtToken();
  const p = token.payload;
  isAdmin = (p["cognito:groups"] || []).includes("admins");
  $("who").textContent = (p.name || p.email) + (isAdmin ? " (admin)" : "");
  $("auth").hidden = true; $("app").hidden = false; $("userbar").hidden = false;
  $("admin-panel").hidden = !isAdmin;
  refresh();
}

// Restore session on page reload
const current = pool.getCurrentUser();
if (current) {
  current.getSession((err, session) => {
    if (!err && session && session.isValid()) startApp(session.getIdToken());
  });
}

// ---------- API ----------
async function api(method, path, body) {
  const res = await fetch(CONFIG.apiUrl + path, {
    method,
    headers: { Authorization: "Bearer " + idToken, "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined
  });
  if (res.status === 401) { logout(); throw new Error("Your session expired. Log in again."); }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || "Request failed");
  return data;
}

async function refresh() {
  try {
    const [courses, mine] = await Promise.all([api("GET", "/courses"), api("GET", "/enrollments")]);
    const enrolled = new Set(mine.map((c) => c.courseId));

    $("courses").innerHTML = courses.length ? courses.map((c) => `
      <li>
        <div><div class="title">${esc(c.title)}</div>
          <div class="meta">${esc(c.instructor)}${c.instructor && c.description ? " – " : ""}${esc(c.description)}</div></div>
        <div class="actions">
          ${enrolled.has(c.courseId)
            ? '<span class="meta">Enrolled</span>'
            : `<button class="small" data-enroll="${esc(c.courseId)}">Enroll</button>`}
          ${isAdmin ? `<button class="small danger" data-delete="${esc(c.courseId)}">Delete</button>` : ""}
        </div>
      </li>`).join("") : '<li class="empty">No courses yet.' + (isAdmin ? " Add the first one above." : " Check back soon.") + "</li>";

    $("mine").innerHTML = mine.length ? mine.map((c) => `
      <li>
        <div><div class="title">${esc(c.title)}</div><div class="meta">${esc(c.instructor)}</div></div>
        <div class="actions"><button class="small outline" data-drop="${esc(c.courseId)}">Drop</button></div>
      </li>`).join("") : '<li class="empty">You have not enrolled in any course.</li>';
  } catch (e) { say($("app-msg"), e.message); }
}

document.addEventListener("click", async (ev) => {
  const t = ev.target;
  try {
    if (t.dataset.enroll) { await api("POST", "/enrollments/" + t.dataset.enroll); say($("app-msg"), "Enrolled.", true); refresh(); }
    if (t.dataset.drop) { await api("DELETE", "/enrollments/" + t.dataset.drop); say($("app-msg"), "Course dropped.", true); refresh(); }
    if (t.dataset.delete && confirm("Delete this course?")) { await api("DELETE", "/courses/" + t.dataset.delete); say($("app-msg"), "Course deleted.", true); refresh(); }
  } catch (e) { say($("app-msg"), e.message); }
});

$("btn-add").onclick = async () => {
  try {
    await api("POST", "/courses", {
      title: $("a-title").value, instructor: $("a-instructor").value, description: $("a-desc").value
    });
    ["a-title", "a-instructor", "a-desc"].forEach((i) => ($(i).value = ""));
    say($("app-msg"), "Course added.", true);
    refresh();
  } catch (e) { say($("app-msg"), e.message); }
};
