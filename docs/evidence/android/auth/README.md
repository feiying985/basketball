# 手机号注册登录 UI 证据

- **基线**：`main`（提交 `dd72d1d`）的黑色首页，冷启动直接进入首页，没有登录页。
  证据：[auth-before-home.png](../auth-before-home.png)。
- **改动后**：冷启动先进入登录页；注册/重置密码页面。
  证据：[auth-login.png](../auth-login.png)、[auth-register.png](../auth-register.png)、
  [auth-reset.png](../auth-reset.png)。
- **登录后首页**：右上角新增账号入口。
  证据：[auth-home-after.png](../auth-home-after.png)。

所有图片由 Robolectric screenshot test 在相同视口（822×1782，`w411dp-h891dp-xhdpi`）、
黑色主题下渲染，不是手工拼接的 mockup：

- `AuthScreenshotTest.kt`：登录/注册/重置密码
- `HomeScreenshotTest.kt`：登录后首页（新增账号图标）

基线图片来自上一版本的实际截图（`docs/evidence/android/black/home-black.png`）；
由于基线没有登录页，基线用「冷启动进入首页」与改动后「冷启动进入登录页」对比。
