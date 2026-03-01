# PvPArena 使用教學 / 配置指南

## 快速開始（3 步）
1) 安裝插件與依賴：將發佈的 `pvparena-<version>-shaded.jar` 放入伺服器 `plugins/`，建議同裝 ProtocolLib 以啟用 1.8 PVP 封包降級。啟動一次產生配置檔。
2) 建立場館：使用 `/arena create <id>`，進場後用 `/arena pos1`、`/arena pos2` 設定邊界，再用 `/arena setspawn1`、`/arena setspawn2` 指定出生點，最後 `/arena save <id>`。
3) 測試：用 `/pvp` 打開主選單加入隊列，用另一名玩家 `/pvp` 加入，或 `/duel <player>` 直接切磋。

## 核心配置 (config.yml)
- `language`: `en` 或自訂 `lang/<code>.yml`。
- `protection.*`: 競技場保護與世界名（留空跳過世界規則）。
- `border.*`: 邊界退出處理（結算或警告傳回）。
- `gui.main/duel`: GUI 尺寸、起始槽位、步進與取消槽位。

## 模式設定 (modes.yml)
- 每個 `modes.<id>` 包含 `displayName`、`icon`、`lore`、`settings`、`kit` 或 `kitRef`。
- `settings.pvp`: `1.8` 啟用舊版 PVP；`1.9` 使用原版冷卻。`noDamageTicks` 可微調打擊間隔（1.8 建議 10）。
- `settings.maxHealth/hunger/saturation`: 進場屬性。
- `kit`: 自定義裝備與藥水；`kitRef` 則引用 kits.yml 的預設套裝。

## 1.8 模式行為 (pvp18.yml)
- 攻速冷卻關閉：`attackSpeed` 高值 + 為玩家打上 scoreboard tag。
- 取消橫掃攻擊、停用副手（可在 pvp18.yml 調整 `disableOffhand`）。
- KB/Combo：`baseKnockback`、`comboKnockback`、`maxCombo`、`sprintKnockbackBonus`、`airborneKnockbackMultiplier` 控制擊退；`comboWindowMs` 定義連擊窗口。
- 分軸 KB：`knockback.horizontalMultiplier`、`knockback.verticalMultiplier` 微調水平/垂直擊退。
- I-frames：`maxNoDamageTicks` 與 `forceIFramesOnHit` 控制受擊無敵時間。
- 跳躍重置：`jumpReset.*` 決定起跳後短時間內的擊退放大。
- 魚竿拉人：`fish.*` 控制距離、拉力與模擬傷害。
- 弓箭：`bow.velocityMultiplier`、`bow.damageMultiplier`、`bow.disablePunch`（關閉 Punch 擊退）、`bow.stripTipped`（強制普通箭）。
- Block-hit：`blockHit.enabled`、`blockHit.windowMs`、`blockHit.damageMultiplier`（揮劍右鍵後短時間減傷）。
- 金蘋果/附魔金蘋果：`goldenApple.*`、`enchantedApple.*` 還原 1.8 效果（黃心、再生、抗性/抗火）。

## 指令與權限（常用）
- `/pvp` 打開主選單；`/duel <player>` 邀請決鬥。
- `/arena create|save|pos1|pos2|setspawn1|setspawn2` 場館管理。
- `/pvparena reload` 重新讀取 config/modes/kits/arenas/messages。
- 建議將管理指令繫結給管理員組；玩家只需使用 `/pvp`、`/duel`。

## 常見檢查
- 邊界未設：`pos1/pos2` 留空時，保護/清理掉落物不會生效。
- 世界名稱：`protection.world` 設錯會跳過世界規則鎖定與提示。
- ProtocolLib 缺失：1.8 封包降級停用但插件仍可運作。

## 仍可補強的 1.8 體感（可再加入）
- 盾牌：比賽中可直接移除盾牌或忽略格擋（目前僅停用副手）。
- 掃擊外的 1.9+ 特性：如攻擊衝量粒子可選擇性關閉。
- 進階 KB 微調：分軸設定、不同武器/擊中狀態的擊退係數。
- 金蘋果/附魔金蘋果：效力還原到 1.8 數值。
- Block-hit 模擬：揮劍格擋短暫減傷的體感。

## 發布包小貼士
- 發布時附上此文件、messages 樣板（`lang/en.yml`、`lang/zh.yml`），並將檔名包含版本號。
