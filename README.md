# 电视直播 TV

把央视频的网页直播页套壳成一个安卓电视直播 App。

网页地址：`https://www.yangshipin.cn/tv/home`

> 本质是「系统 WebView + 一段注入脚本」。不抓流、不破解，就是让电脑版网页在一个
> 没有浏览器控件的全屏壳里跑起来，再把播放器抠出来铺满整个屏幕。

---

## 功能

| 项目 | 说明 |
|---|---|
| 频道表 | CCTV-1 ~ CCTV-17 + 31 个省级卫视 = 48 个台 |
| 遥控器 | `OK` 呼出频道列表，`▲` `▼` 换台，`返回` 关列表 / 再按退出 |
| 换台 | 优先改页面 `location.hash` 走前端路由，**不整页刷新**；失败才整页加载 |
| 黑遮罩 | 换台期间全屏遮罩显示频道号 + 频道名，画面一出立刻撤掉 |
| 备用源 | 20 秒没画面 → 自动切备用源（央视 `tv.cctv.com`，卫视各自官网） |
| 栏目信息 | 注入脚本从页面里提取当前节目名，显示在频道列表里 |
| UA 伪装 | 伪装成桌面 Chrome，版本号读**系统真实内核版本**，不写死 |
| 内核自检 | 启动检查 WebView 版本，低于 Chrome 60 直接提示 |
| 浮层清理 | 登录弹窗 / 下载 App 横幅 / 右侧节目单 —— 按位置和尺寸判断，**不写死 class 名** |

---

## 目录结构

```
YangshiTV/
├── app/src/main/
│   ├── assets/
│   │   ├── channels.json               ← 频道表，加台改这里
│   │   └── inject.js                   ← 页面注入脚本（核心）
│   ├── java/com/hutou/yangshitv/
│   │   ├── MainActivity.kt             ← 壳 + 遥控器 + 换台引擎
│   │   ├── ChannelOverlay.kt           ← 频道列表浮层
│   │   └── Channels.kt                 ← 频道模型 / 频道表读取 / UA 工具
│   └── res/
├── tools/make_icon.py                  ← 图标生成脚本（纯标准库）
└── .github/workflows/
    ├── build.yml                       ← debug 构建
    └── release.yml                     ← 签名构建 + 发 Release
```

---

## 怎么改

**加 / 改频道** —— 只动 `app/src/main/assets/channels.json`：

```json
{ "no": 49, "name": "某某卫视", "pid": "6000xxxxx", "backup": "https://..." }
```

**调浮层清理规则** —— 动 `app/src/main/assets/inject.js` 里的 `shouldHide()`，
里面 A~E 五条规则都用「位置 + 尺寸比例」判定，没有一处依赖 class 名。

**调试**：应用内任意位置执行 `window.__HTV__.diag()` 看当前状态；
`window.__HTV__.restore()` 可以把被误杀的浮层全部恢复。

---

## 本地构建

工程刻意不用 Gradle Wrapper（沙箱里生成不了），CI 用 `gradle/actions/setup-gradle` 拉 Gradle 8.7。

本地有 Gradle 8.7+ 和 Android SDK 的话：

```bash
gradle assembleDebug
```

没有 Android SDK 也没关系 —— 推到 GitHub 上让 Actions 编，见 `.github/workflows/`。

---

## 签名

Release 包由 CI 签名，密钥经 `gh secret` 注入：

| Secret | 含义 |
|---|---|
| `KEYSTORE_BASE64` | keystore 文件 base64 后的内容 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥密码 |

密钥文件本身**不在仓库里**（`.gitignore` 已排除 `*.keystore` / `*.jks`）。

---

## 免责

仅用于个人在自有设备上观看央视频公开的电视直播页面。内容版权归原权利人所有。
