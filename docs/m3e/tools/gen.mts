/**
 * Author the KAZE-SLAUNCHER UI redesign on the real m3e-canvas engine.
 *
 * m3e-canvas (github.com/lnkiai/m3e-canvas, MIT) is a browser tool: sketch Material 3
 * Expressive screens, then export a vibe-coding prompt. Its document model is
 * `Doc = { frames, groups, paletteKey, theme }`; its prompt engine is `lib/prompt.ts`.
 * This script builds the document in code, checks the geometry, and exports
 *   1. the canvas document (importable into the editor)
 *   2. the share link (#docz=) that opens the design in the canvas
 *   3. the prompt the tool itself generates — per screen, and for the whole app
 *
 * Token reference (lib/tokens.ts):
 *   - `Item.size`   fixed width in dp ... EXCEPT on `text`, where it is the FONT SIZE
 *   - `Item.size2`  height for free-form kinds (box, card, image)
 *   - groups are one row (axis "x") or one column (axis "y")
 *   - an overlap between two groups is read as "layered inside" -> a plain stack must not overlap
 */
import { writeFileSync, mkdirSync } from "node:fs";
import { deflateRawSync } from "node:zlib";
import { buildPrompt } from "../m3e-repo/lib/prompt.ts";
import { isProject } from "../m3e-repo/lib/project.ts";
import { KIND_SPEC } from "../m3e-repo/lib/tokens.ts";
import type { Doc, Frame, Group, Item, Kind, Variant } from "../m3e-repo/lib/tokens.ts";

/* ---------- geometry ---------- */
const PW = 412;
const PH = 892;
const M = 16;
const CW = PW - M * 2; // 380
const GAPX = 140;
const GAP = 8;
/** the frame sits below the canvas origin so the status strip fits above it */
const FY = 64;
/** the advisory status strip drawn above every frame (SystemUI, not part of the app) */
const STATUS_H = 32;
const FY_CONTENT = FY + STATUS_H + 8;
/** the bottom bar keeps the last 80dp of the frame */
const BODY_BOTTOM = PH - 80;

let n = 0;
const uid = (p = "i") => `${p}${(++n).toString(36)}`;

/* ---------- measurement, standing in for the browser's text metrics ---------- */
/** the width the browser would measure for a content-sized part (goes into `size` and the prompt) */
const measured: Record<string, number> = {};
/** the width the sketch lays out with: the fixed `size` for sized kinds, the measurement otherwise */
const layoutW: Record<string, number> = {};
/** kinds whose width is a fixed `size`, never a measurement */
const SIZED: Kind[] = ["box", "image", "fab", "iconButton", "fabMenu", "navRail", "camera", "map", "loadingIndicator", "circularProgress"];
/** kinds that span the content width */
const CONTENT_KINDS: Kind[] = ["card", "listItem", "searchBar", "textField", "switch", "select", "tabs"];

const textW = (s: string) => [...s].reduce((a, c) => a + (c.charCodeAt(0) > 0x2e80 ? 14 : 8), 0);
function estWidth(kind: Kind, label: string, o: { icon?: string | null; width?: number }): number {
  const t = textW(label);
  switch (kind) {
    case "button":
      return o.width ?? Math.max(64, Math.round(t + (o.icon ? 26 : 0) + 32));
    case "extendedFab":
      return o.width ?? Math.round(t + (o.icon ? 26 : 0) + 40);
    case "chip":
      return o.width ?? Math.round(t + (o.icon ? 24 : 0) + 28);
    case "badge":
      return Math.max(16, Math.round(t + 16));
    case "text":
      return o.width ?? Math.max(40, t);
    default:
      return o.width ?? CW;
  }
}

type Opt = Partial<Item> & { width?: number };
/**
 * One part. `size` is a fixed width except on `text` (font size); `width` is the measured
 * width of a content-sized part. Keeping those two apart is the whole trick.
 */
function it(kind: Kind, label: string, o: Opt = {}): Item {
  const id = o.id ?? uid(kind.slice(0, 2));
  const variant: Variant = o.variant ?? (kind === "chip" || kind === "textField" ? "outlined" : "filled");
  const item = { id, kind, label, icon: o.icon ?? null, variant, ...o } as Item;
  measured[id] = kind === "text" ? (o.width ?? textW(label)) : estWidth(kind, label, o);
  layoutW[id] = SIZED.includes(kind) ? (o.size ?? KIND_SPEC[kind].w) : measured[id];
  return item;
}

/** the height a part takes in the sketch */
const hOf = (i: Item) => i.size2 ?? KIND_SPEC[i.kind].h;

const NAV = (selected: number): Item =>
  it("bottomNav", "", {
    variant: "filled",
    size: PW,
    selected,
    tabs: [
      { icon: "home", label: "主页" },
      { icon: "dns", label: "服务端" },
      { icon: "terminal", label: "控制台" },
      { icon: "settings", label: "设置" },
    ],
    note: "常驻底栏：**这一处保留现有设计，本次重构不动它**。列在草图里只为了确认它相对内容的位置与高度。",
  });

/* ---------- the document under construction ---------- */
const frames: Frame[] = [];
const groups: Group[] = [];
/**
 * The advisory status strips live outside `groups`: they are a reader's aid on the canvas,
 * not part of any screen, and `buildPrompt` would otherwise list them as stray parts.
 */
const annotations: { x: number; y: number; text: string }[] = [];
const nav = { home: "", server: "", console: "", settings: "", detail: "", newServer: "", addons: "", logs: "", appearance: "", runtime: "" };

/**
 * One screen. A cursor walks down the frame, so two runs never overlap — an overlap is
 * how the engine infers "layered inside", and a plain stack must not look like one.
 */
class Screen {
  readonly id: string;
  private readonly x0: number;
  private y = FY_CONTENT;
  private readonly body: Group[] = [];

  constructor(readonly name: string, readonly note: string, col: number) {
    this.x0 = col * (PW + GAPX);
    this.id = uid("f");
    frames.push({ id: this.id, name, x: this.x0, y: FY, bg: "surface", note, place: "top" });
    // the advisory status strip, kept out of `groups` so it stays off the generated prompt
    annotations.push({ x: this.x0, y: FY - 32, text: `↑ ${name} 的状态栏（SystemUI，不属于本应用）` });
  }

  /** one part on its own line, spanning the content width */
  line(...items: Item[]): void {
    for (const item of items) {
      if (!SIZED.includes(item.kind)) layoutW[item.id] = Math.min(layoutW[item.id], CW);
      this.body.push({ id: uid("g"), x: this.x0 + M, y: this.y, axis: "x", items: [item] });
      this.y += hOf(item) + GAP;
    }
  }
  /** parts side by side, each its own run; wraps to a second row like a FlowRow */
  row(...items: Item[]): void {
    items.forEach((item, index) => {
      if (!item || typeof item !== "object") {
        throw new Error(`${this.name}: row() got ${typeof item} at index ${index} of ${items.length} — a stray comma in the call`);
      }
    });
    let cx = this.x0 + M;
    let tallest = 0;
    for (const item of items) {
      const w = layoutW[item.id];
      if (cx > this.x0 + M && cx + w > this.x0 + M + CW) {
        this.y += tallest + GAP;
        cx = this.x0 + M;
        tallest = 0;
      }
      this.body.push({ id: uid("g"), x: cx, y: this.y, axis: "x", items: [item] });
      cx += w + GAP;
      tallest = Math.max(tallest, hOf(item));
    }
    this.y += tallest + GAP;
  }
  /** parts fused on their facing edges: a real connected group in the canvas */
  fuse(...items: Item[]): void {
    this.body.push({ id: uid("g"), x: this.x0 + M, y: this.y, axis: "x", items });
    this.y += Math.max(...items.map(hOf)) + GAP;
  }
  space(dp = 16): void {
    this.y += dp;
  }
  /** the always-present bottom bar */
  finish(selected: number): void {
    const bar = NAV(selected);
    layoutW[bar.id] = PW;
    groups.push({ id: uid("g"), x: this.x0, y: FY + BODY_BOTTOM, axis: "x", items: [bar] });
    groups.push(...this.body);
  }
}

/* ============ 1 主页 ============ */
{
  const s = new Screen("主页", "打开应用后的落地屏：一眼看到「服务器现在是什么状态」，并且可以立刻启停", 0);
  s.row(
    it("loadingIndicator", "", { size: 40, variant: "filled", note: "服务状态指示：运行中缓慢旋转，启动中加速，停止时静止成一个固定造型" }),
    it("chip", "运行中 02:14:37", { icon: "bolt", checked: true, variant: "tonal", note: "状态胶囊：状态色圆点 + 已运行时长" })
  );
  s.line(
    it("card", "生存服", {
      variant: "elevated",
      noImage: true,
      icon: "dns",
      supporting: "Paper 1.21.4 · Java 21 · 4096 MB · 端口 25565 · 在线 0 人 · 内存 1.8 / 4.0 GB",
      size2: 112,
      note: "当前实例卡：点一下展开实例列表切换，卡片本身就是选择器（取代原来的下拉菜单）。正文那行带上「在线人数 / 内存 / 端口」，所以这一屏不再单独放一张运行概况卡",
    })
  );
  s.fuse(
    it("button", "启动服务端", { icon: "play_arrow", variant: "filled", note: "主行动：停止状态下是「启动服务端」；启动过程中变成「取消启动」，全程可点，用户在任何阶段都能中止" }),
    it("button", "停止", { icon: "stop", variant: "tonal", note: "只在运行中或启动中可点" })
  );
  s.space(4);
  s.line(
    it("card", "已运行 02:14:37", {
      variant: "outlined",
      noImage: true,
      icon: "monitoring",
      supporting: "在线 3 人 · 内存 1.8 / 4.0 GB",
      size2: 88,
      note: "运行时长用大号等宽数字，一眼可读；服务器没跑时显示「未运行」，不要显示 00:00:00",
    })
  );
  s.line(
    it("listItem", "Linux 环境已就绪", {
      icon: "check_circle",
      supporting: "proot + Ubuntu 24.04 · 已安装 Java 17 / 21",
      iconFill: "primaryContainer",
      note: "环境状态行：未部署时行尾出现「部署」按钮；部署中这一行变成波浪进度条 + 当前步骤文案",
    })
  );
  s.space(4);
  s.row(
  );
  s.finish(0);
}

/* ============ 2 服务端 ============ */
{
  const s = new Screen("服务端", "服务端实例的列表与管理：多开、筛选、启停、导入", 1);
  s.row(
    it("button", "新建", { icon: "add", variant: "filled", note: "打开新建向导" }),
    it("button", "导入 jar", { icon: "file_open", variant: "tonal", note: "用系统文件选择器挑一个已有的 server.jar 导入" })
  );
  s.fuse(
    it("chip", "全部 3", { checked: true, variant: "tonal", note: "分段筛选的选中项" }),
    it("chip", "官方 1", { variant: "outlined", note: "Vanilla / Paper" }),
    it("chip", "性能 1", { variant: "outlined", note: "Purpur / Spigot" }),
    it("chip", "模组 1", { variant: "outlined", note: "Fabric / Forge / NeoForge" })
  );
  s.line(
    it("listItem", "生存服", {
      icon: "dns",
      supporting: "Paper 1.21.4 · Java 21 · 4096 MB · 运行中",
      iconFill: "primaryContainer",
      note: "实例行：点开实例详情；左侧是运行状态徽标；行尾是启停按钮与 ⋮ 菜单",
    }),
    it("listItem", "创造服", {
      icon: "dns",
      supporting: "Paper 1.20.4 · Java 17 · 2048 MB · 已停止",
      iconFill: "secondaryContainer",
    }),
    it("listItem", "模组服", {
      icon: "extension",
      supporting: "Fabric 1.21.1 · Java 21 · 6144 MB · EULA 未接受",
      iconFill: "tertiaryContainer",
      note: "EULA 未接受的实例必须在行内直接点出来，不能藏进详情页",
    })
  );
  s.space(4);
  s.fuse(
    it("button", "启动全部", { icon: "play_arrow", variant: "filled", note: "启动当前筛选出的全部实例" }),
    it("button", "停止全部", { icon: "stop", variant: "tonal", note: "停止全部运行中的实例" })
  );
  s.finish(1);
}

/* ============ 3 控制台 ============ */
{
  const s = new Screen("控制台", "实时日志与命令输入：这是这个应用的主工作台", 2);
  s.fuse(
    it("chip", "运行中", { checked: true, variant: "tonal", note: "状态胶囊，带状态色圆点" })
  );
  s.line(
    it("card", '[12:04:31 INFO]: Preparing level "world"\n[12:04:33 INFO]: Preparing spawn area: 46%\n[12:04:35 INFO]: Done (4.812s)! For help, type "help"', {
      variant: "filled",
      fill: "inverseSurface",
      size2: 500,
      noImage: true,
      checked: true,
      note: "终端面板：等宽字体，按日志级别着色（INFO 用高对比浅色、WARN 琥珀、ERROR 红、命令回显蓝）。这是「终端画布」而不是普通卡片，除圆角外不要加装饰。新日志到达时自动滚到底；用户手动上滑就暂停跟随，并出现「回到底部」按钮",
    })
  );
  s.fuse(
    it("textField", "输入命令…", { variant: "outlined", width: 316, note: "命令输入：服务端没运行时禁用，占位文字换成「服务端运行后可输入命令」" })
  );
  s.finish(2);
}

/* ============ 4 实例详情 ============ */
{
  const s = new Screen("实例详情", "一个服务端的全部控制项：运行、配置、世界、备份、日志、附加组件", 3);
  s.row(
    it("text", "生存服", { variant: "text", size: 22, width: 120, bold: true, note: "实例名，点一下可以重命名" })
  );
  s.line(
    it("tabs", "", {
      size: CW,
      selected: 0,
      tabs: [
        { icon: "", label: "运行" },
        { icon: "", label: "配置" },
        { icon: "", label: "世界" },
        { icon: "", label: "附加" },
      ],
      note: "实例内的四个分区：运行状态 / server.properties / 世界与备份 / 插件与模组",
    })
  );
  s.line(
    it("card", "运行状态", {
      variant: "elevated",
      noImage: true,
      icon: "monitoring",
      supporting: "已运行 02:14:37 · 在线 3 人 · 内存 1.8 / 4.0 GB · 端口 25565",
      size2: 112,
      note: "状态卡：左侧大号运行时长，右侧内存占用；内存超过 80% 时换成 error 色",
    })
  );
  s.line(it("linearProgress", "内存占用", { value: 45, wavy: true, note: "内存占用，波浪进度条；超过 80% 换成 error 色" }));
  s.line(
    it("listItem", "自动备份", { icon: "history", supporting: "每 30 分钟 · 最近 今天 12:00 · 共 4 份", iconFill: "secondaryContainer" }),
    it("listItem", "查看启动日志", { icon: "receipt_long", supporting: "每次启动的完整输出与退出码", iconFill: "secondaryContainer" }),
    it("listItem", "插件 / 模组", { icon: "extension", supporting: "从 Modrinth 搜索并安装", iconFill: "secondaryContainer" })
  );
  s.space(4);
  s.line(it("button", "停止服务端", { icon: "stop", variant: "filled", width: CW, note: "主行动：运行中是「停止服务端」，已停止是「启动服务端」" }));
  s.finish(1);
}

/* ============ 5 新建服务端 ============ */
{
  const s = new Screen("新建服务端", "三步向导：选核心 → 选版本与资源 → 确认安装", 4);
  s.row(
    it("text", "新建服务端", { variant: "text", size: 22, width: 140, bold: true, note: "向导标题" })
  );
  s.line(it("linearProgress", "第 1 步，共 3 步", { value: 33, wavy: true, note: "向导进度：三步分别是「选核心」「选版本与资源」「确认安装」" }));
  s.line(
    it("card", "Vanilla 官方原版", {
      variant: "filled",
      noImage: true,
      icon: "deployed_code",
      supporting: "Mojang 官方 · 兼容性最好",
      size2: 96,
      note: "选中的核心：主色容器色底 + 右上角对勾，三选一",
    }),
    it("card", "Paper 性能优化", { variant: "outlined", noImage: true, icon: "bolt", supporting: "插件生态最好 · 性能更强", size2: 96 }),
    it("card", "导入自定义 jar", { variant: "outlined", noImage: true, icon: "folder_open", supporting: "已经有 server.jar？直接导入", size2: 96 }),
    it("card", "可选项（建服时就定下来）", {
      variant: "outlined",
      noImage: true,
      icon: "tune",
      supporting: "端口 25565 · 最大玩家 20 · 正版验证 开 · 游戏模式 生存",
      size2: 96,
      note: "这一组是可选覆盖项：留空就用默认值，省得建完再进实例详情改",
    })
  );
  s.space(4);
  s.fuse(it("button", "下一步", { icon: "arrow_forward", variant: "filled", note: "第一步必须选中一个核心才能点" }));
  s.finish(1);
}

/* ============ 6 插件与模组 ============ */
{
  const s = new Screen("插件与模组", "从 Modrinth 搜索并一键安装到当前实例", 5);
  s.row(
    it("text", "插件", { variant: "text", size: 22, width: 90, bold: true, note: "标题：插件 / 模组，跟着实例的核心类型变" })
  );
  s.line(it("searchBar", "搜索 Modrinth", { variant: "filled", icon: "search", width: CW, note: "搜索框：回车发起搜索，搜索中变成加载指示器" }));
  s.fuse(
    it("chip", "适合 1.21.4", { checked: true, variant: "tonal", note: "按当前实例的 MC 版本过滤" }),
    it("chip", "服务端", { variant: "outlined", note: "只显示服务端可用的项目" }),
    it("chip", "下载最多", { variant: "outlined", note: "排序方式" })
  );
  s.line(
    it("listItem", "LuckPerms", {
      icon: "key",
      supporting: "权限管理 · 128M 下载",
      iconFill: "primaryContainer",
      note: "搜索结果行：点开项目详情；行尾是安装按钮，安装中变成进度",
    }),
    it("listItem", "EssentialsX", { icon: "handyman", supporting: "基础指令集 · 96M 下载", iconFill: "secondaryContainer" }),
    it("listItem", "WorldEdit", { icon: "brush", supporting: "地形编辑 · 210M 下载", iconFill: "tertiaryContainer" })
  );
  s.space(4);
  s.line(it("extendedFab", "安装所选", { icon: "download", variant: "tonal", note: "批量安装；下载中显示波浪进度，并且可以取消" }));
  s.finish(1);
}

/* ============ 7 启动日志 ============ */
{
  const s = new Screen("启动日志", "每次启动的历史记录：退出码、耗时、失败原因", 6);
  s.row(
    it("text", "启动日志", { variant: "text", size: 22, width: 110, bold: true, note: "标题" })
  );
  s.fuse(
    it("chip", "全部", { checked: true, variant: "tonal", note: "筛选：全部" }),
    it("chip", "失败", { variant: "outlined", note: "只看启动失败的那几次" })
  );
  s.line(
    it("listItem", "今天 12:04 · 成功", {
      icon: "check_circle",
      supporting: "启动耗时 4.8s · 退出码 0 · Java 21",
      iconFill: "primaryContainer",
      note: "一次启动一行，图标和颜色直接表达成功 / 失败，不要让用户点进去才知道结果",
    }),
    it("listItem", "今天 09:31 · 失败", {
      icon: "error",
      supporting: "UnsupportedClassVersionError · 需要 Java 21，当前 17",
      iconFill: "tertiaryContainer",
      note: "失败原因直接写在摘要里",
    }),
    it("listItem", "昨天 21:12 · 成功", { icon: "check_circle", supporting: "启动耗时 5.1s · 退出码 0 · Java 21", iconFill: "primaryContainer" })
  );
  s.space(4);
  s.fuse(
    it("button", "导出全部", { icon: "download", variant: "filled", note: "打包导出为一个 txt 或 zip" }),
    it("button", "清空记录", { icon: "delete_sweep", variant: "text", note: "清空历史，必须二次确认" })
  );
  s.finish(1);
}

/* ============ 8 外观设置 ============ */
{
  const s = new Screen("外观设置", "主题、颜色与字体：应用长什么样", 7);
  s.line(
    it("card", "外观", {
      variant: "elevated",
      noImage: true,
      icon: "palette",
      supporting: "主题模式 · 颜色来源 · 玻璃强度",
      size2: 112,
      note: "分组卡：一张卡就是一个设置分组，卡片内是若干设置行",
    }),
    it("listItem", "主题模式", { icon: "brightness_6", supporting: "跟随系统", iconFill: "secondaryContainer", note: "跟随系统 / 浅色 / 深色，点开是分段选择" }),
    it("listItem", "颜色来源", { icon: "colorize", supporting: "壁纸动态取色", iconFill: "secondaryContainer", note: "壁纸动态取色 / 自定义主色（点开是取色器）" }),
    it("listItem", "深色样式", { icon: "dark_mode", supporting: "普通黑", iconFill: "secondaryContainer", note: "普通黑 / AMOLED 纯黑" })
  );
  s.space(4);
  s.line(
    it("card", "字体与动效", {
      variant: "elevated",
      noImage: true,
      icon: "animation",
      supporting: "强调字重 · 动效方案",
      size2: 88,
    }),
    it("switch", "标题与标签使用强调字重", { checked: true, note: "M3 Expressive 的 emphasized 字体样式" }),
    it("switch", "表现力弹簧动效", { checked: true, note: "关掉则退回标准的匀速过渡" })
  );
  s.finish(3);
}

/* ============ 9 运行与存储 ============ */
{
  const s = new Screen("运行与存储", "实例目录、电池优化、Java、更新通道与许可证", 8);
  s.line(
    it("card", "存储", {
      variant: "elevated",
      noImage: true,
      icon: "folder",
      supporting: "实例目录 · 占用空间",
      size2: 88,
      note: "分组卡",
    }),
    it("listItem", "实例目录", { icon: "folder_open", supporting: "默认位置 · 已用 2.4 GB", iconFill: "secondaryContainer", note: "点开用系统目录选择器换位置，换完自动重新扫描已有服务端" }),
    it("listItem", "已安装的 Java", { icon: "coffee", supporting: "Java 17 · Java 21", iconFill: "secondaryContainer", note: "点开可以单独安装或卸载某个 Java 版本" })
  );
  s.space(4);
  s.line(
    it("card", "后台与更新", {
      variant: "elevated",
      noImage: true,
      icon: "tune",
      supporting: "电池优化 · 更新通道 · 自动检查",
      size2: 88,
    }),
    it("listItem", "电池优化", { icon: "battery_saver", supporting: "已加入白名单", iconFill: "secondaryContainer", note: "没加入白名单时高亮提示，点一下跳系统设置" }),
    it("switch", "启动时自动检查更新", { checked: true }),
    it("select", "更新通道", { icon: "alt_route", supporting: "预览版", note: "稳定版 / 预览版" })
  );
  s.space(4);
  s.line(it("button", "关于与许可证", { icon: "gavel", variant: "outlined", width: CW, note: "GPL-3.0 全文与第三方组件声明" }));
  s.finish(3);
}

/* ---------- navigation ---------- */
const byName = (name: string) => {
  const f = frames.find((x) => x.name === name);
  if (!f) throw new Error(`no frame named ${name}`);
  return f.id;
};
nav.home = byName("主页");
nav.server = byName("服务端");
nav.console = byName("控制台");
nav.settings = byName("外观设置");
nav.detail = byName("实例详情");
nav.newServer = byName("新建服务端");
nav.addons = byName("插件与模组");
nav.logs = byName("启动日志");
nav.appearance = byName("外观设置");
nav.runtime = byName("运行与存储");

const tap = (item: Item, to: string, transition: "slide" | "slideUp" | "slideDown" | "slideLeft" | "fade" | "expand" = "slide") => {
  item.action = { to, transition };
};
for (const g of groups) {
  for (const item of g.items) {
    if (item.kind === "bottomNav") {
      item.actions = {
        "tab:0": { to: nav.home, transition: "fade" },
        "tab:1": { to: nav.server, transition: "fade" },
        "tab:2": { to: nav.console, transition: "fade" },
        "tab:3": { to: nav.appearance, transition: "fade" },
      };
      continue;
    }
    if (item.icon === "arrow_back") tap(item, "back", "slideLeft");
    if (item.icon === "close") tap(item, nav.home, "slideDown");
    if (item.label === "新建") tap(item, nav.newServer, "slideUp");
    if (item.kind === "listItem" && item.label === "生存服") tap(item, nav.detail);
    if (item.label === "插件 / 模组") tap(item, nav.addons);
    if (item.label === "查看启动日志") tap(item, nav.logs);
    if (item.label === "启动服务端") tap(item, nav.console, "expand");
  }
}

const doc: Doc = {
  groups,
  frames,
  paletteKey: "purple",
  dynamicColor: true,
  frame: "phone",
  platform: "android",
  title: "KAZE SLauncher 界面重构",
  brief:
    "Android 上运行 Minecraft Java 服务端的启动器。核心链路：部署自包含 Linux 环境（proot + Ubuntu rootfs）→ 按 MC 版本自动安装 Java → 下载或导入服务端核心 → 首次启动自动处理 eula.txt → 实时控制台看日志并输入命令。数据模型是「多实例」：每个服务端实例有独立的目录、Java 版本、内存上限、控制台日志与运行状态。这是对既有应用的一次界面重构：后端、功能与常驻底栏全部保留，只重做其余界面层。",
  theme: { dark: false, bothModes: true, contrast: "standard", shape: "rounded", font: "robotoFlex", emphasized: true, motion: "expressive" },
};

/* ---------- geometry checks ---------- */
if (!isProject(doc)) throw new Error("generated document fails isProject()");
const kindSet = new Set<string>();
for (const g of groups) for (const i of g.items) kindSet.add(i.kind);
console.log(`frames=${frames.length} groups=${groups.length} items=${groups.reduce((a, g) => a + g.items.length, 0)}`);
console.log(`kinds(${kindSet.size}): ${[...kindSet].sort().join(", ")}`);

const rectOf = (g: Group) => {
  let w = 0;
  let h = 0;
  if (g.axis === "x") {
    for (const i of g.items) {
      w += layoutW[i.id] ?? i.size ?? KIND_SPEC[i.kind].w;
      h = Math.max(h, hOf(i));
    }
    w += GAP * (g.items.length - 1);
  } else {
    for (const i of g.items) {
      h += hOf(i) + GAP;
      w = Math.max(w, layoutW[i.id] ?? i.size ?? KIND_SPEC[i.kind].w);
    }
  }
  return { l: g.x, t: g.y, r: g.x + w, b: g.y + h };
};
const isBar = (g: Group) => g.items.some((i) => i.kind === "bottomNav");
const isStrip = (g: Group) => g.id.startsWith("sb");
const rectFor = (g: Group, f: Frame) => (isBar(g) ? { l: f.x, t: f.y + BODY_BOTTOM, r: f.x + PW, b: f.y + PH } : rectOf(g));
const describe = (g: Group) => g.items.map((i) => `${i.kind}:${i.label.slice(0, 16) || i.icon}`).join(",");

const problems: string[] = [];
for (const f of frames) {
  // a group belongs to this frame when it starts inside the frame's horizontal band
  const mine = groups.filter((g) => {
    if (isStrip(g)) return false;
    const r = rectOf(g);
    return r.l >= f.x - 4 && r.l < f.x + PW && g.y >= f.y - 4 && g.y <= f.y + PH;
  });
  const barTop = f.y + BODY_BOTTOM;
  for (const g of mine) {
    if (isBar(g)) continue;
    const r = rectOf(g);
    if (r.r > f.x + PW) problems.push(`${f.name}: ${describe(g)} overflows the frame by ${Math.round(r.r - f.x - PW)}dp`);
    if (r.b > barTop) problems.push(`${f.name}: ${describe(g)} runs ${Math.round(r.b - barTop)}dp under the bottom bar`);
  }
  for (let a = 0; a < mine.length; a++) {
    for (let b = a + 1; b < mine.length; b++) {
      if (isBar(mine[a]) && isBar(mine[b])) continue;
      const A = rectFor(mine[a], f);
      const B = rectFor(mine[b], f);
      if (A.l < B.r && B.l < A.r && A.t < B.b && B.t < A.b) {
        problems.push(`${f.name}: ${describe(mine[a])} <-> ${describe(mine[b])} overlap`);
      }
    }
  }
}
if (problems.length) {
  console.log(`\n!! ${problems.length} geometry problem(s):`);
  for (const p of problems) console.log(`   - ${p}`);
} else {
  console.log("geometry: OK — nothing overflows, nothing overlaps");
}

/* ---------- output ---------- */
const outDir = process.argv[2] ?? ".";
mkdirSync(outDir, { recursive: true });

const json = JSON.stringify(doc, null, 2);
writeFileSync(`${outDir}/kaze-redesign.m3e.json`, json, "utf8");
writeFileSync(`${outDir}/kaze-redesign.canvas-notes.json`, JSON.stringify(annotations, null, 2), "utf8");

const b64url = deflateRawSync(Buffer.from(json, "utf8")).toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
writeFileSync(`${outDir}/kaze-redesign.share.txt`, `https://lnkiai.github.io/m3e-canvas/#docz=${b64url}\n`, "utf8");

const all = buildPrompt(doc, measured, undefined, "zh");
writeFileSync(`${outDir}/kaze-redesign.prompt.md`, all, "utf8");
for (const f of frames) writeFileSync(`${outDir}/prompt-${f.name}.md`, buildPrompt(doc, measured, f.id, "zh"), "utf8");
console.log(`share link ${b64url.length} chars · whole-app prompt ${all.length} chars · written to ${outDir}`);
