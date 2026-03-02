# PvPArena 使用教學 / 配置指南

## 快速開始（3 步）
1) 安裝插件：將發佈的 `pvparena-light-<version>.jar`（thin jar）放入伺服器 `plugins/`，建議同裝 ProtocolLib 以啟用 1.8 PVP 封包降級。啟動一次產生配置檔。
2) 建立場館：使用 `/arena create <id>`，進場後用 `/arena pos1`、`/arena pos2` 設定邊界，再用 `/arena setspawn1`、`/arena setspawn2` 指定出生點，最後 `/arena save <id>`。
3) 測試：用 `/pvp` 打開主選單加入隊列，用另一名玩家 `/pvp` 加入，或 `/duel <player>` 直接切磋。

## 核心配置 (config.yml)
- `language`: `en` 或自訂 `lang/<code>.yml`。
- `protection.*`: 競技場保護與世界名（留空跳過世界規則）。
- `border.*`: 邊界退出處理（結算或警告傳回）。
- `gui.main/duel`: GUI 尺寸、起始槽位、步進與取消槽位。
- `match.winner-leave-wait.*`: 撿裝等待環節音樂（可開關/改音效）。

```yml
match:
	winner-leave-wait:
		play-sound: true
		sound: "music_disc.otherside"
		volume: 1.0
		pitch: 1.0
```

## 模式設定 (modes.yml)
- 每個 `modes.<id>` 包含 `displayName`、`icon`、`lore`、`settings`、`kit` 或 `kitRef`。
- `settings.pvp`: `1.8` 啟用舊版 PVP；`1.9` 使用原版冷卻。`noDamageTicks` 可微調打擊間隔（1.8 建議 10）。
- `settings.maxHealth/hunger/saturation`: 進場屬性。
- `kit`: 自定義裝備與藥水；`kitRef` 則引用 kits.yml 的預設套裝。

## 指令與權限（常用）
- `/pvp` 打開主選單；`/duel <player>` 邀請決鬥；`/duel dleave` 可在撿裝等待時提前離場。
- `/arena create|save|pos1|pos2|setspawn1|setspawn2` 場館管理。
- `/pvparena reload` 重新讀取 config/modes/kits/arenas/messages。
- 建議將管理指令繫結給管理員組；玩家只需使用 `/pvp`、`/duel`。

## 依賴下載行為（SQLite）
- 本插件目前是 thin jar，不內嵌 sqlite-jdbc。
- 啟動時若偵測缺少 sqlite 驅動，會自動下載到 `plugins/lib`。
- 若 `plugins/lib/sqlite-jdbc-3.45.3.0.jar` 已存在，會直接略過下載。


