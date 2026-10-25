# MPV 库分支问题审查与修复设计文档

> 作者：Shiro
> 日期：2026-10-25
> 审查对象：`https://github.com/chengshengru/media` → 分支 `release-1.11.0-fongmi-libass` → 模块 `libraries/mpvplayer`
> 基线提交：`1324f59`（2026-09-22，`新增lib-exoplayer-libass和lib-ui-libass模块`）
> 关联文档（App 侧，另一工程）：[MPV引擎STATE_ENDED补齐开发设计文档.md](./MPV引擎STATE_ENDED补齐开发设计文档.md)
> 说明：本文档只输出审查结论与修复设计，**不修改任何代码**；修复任务另行开单。

---

## 一、审查结论摘要

| 编号 | 问题 | 等级 | 是否阻断「TV 端自动连播」 | 建议 |
| --- | --- | --- | --- | --- |
| LIB-1 | `REPEAT_MODE_ONE` 下的 seek 短路分支会误伤正常 seek | P1（中） | 否 | 修复 |
| LIB-2 | `END_FILE` 未区分 `reason`，`REDIRECT`/`QUIT` 会被当作播放结束 | P1（中） | 否 | 修复 |
| LIB-3 | `COMMAND_SEEK_TO_PREVIOUS` 可用性判定过度声明 | P2（低-中） | 否 | 修复 |
| LIB-4 | `addSubtitle` 吞掉命令失败返回值，向调用方谎报成功 | P2（低-中） | 否 | 修复 |
| LIB-5 | 「拖到片尾」结束后首次按播放被吞一次 | P3（低） | 否 | 可选修复 |
| LIB-6 | `REPEAT_MODE_ONE` 用「重载文件」模拟循环，非无缝 | P3（低） | 否 | 改进评估，本轮不改 |

**核心结论（重要）**：

1. 该分支**在真实 EOF 时会正常产生 `Player.STATE_ENDED`**，事件链路完整，不存在「STATE_ENDED 缺失」这一缺陷。
2. 「`REPEAT_MODE_ONE` 下不产生 ENDED」是 media3 契约内的**预期行为**（循环播放本就不该自动下一集），**不要在库侧改成 ENDED**，否则会破坏循环语义并导致上层双跳集。
3. App 侧「播完一集不自动下一集」的根因是**重复模式跨集/跨内容粘连**，属 App 工程问题，见关联文档；**不要把修复放到库侧**。
4. 上述 6 条均为库内部的健壮性/契约一致性问题，可按优先级独立修复，不阻塞 App 侧修复。

---

## 二、审查范围与方法

| 项 | 内容 |
| --- | --- |
| 仓库 / 分支 | `chengshengru/media` / `release-1.11.0-fongmi-libass` |
| 基线提交 | `1324f5974357af3a149c8cd75faa6386c1690a08` |
| 审查模块 | `libraries/mpvplayer/src/main/java/androidx/media3/mpvplayer/**` 及 `src/main/java/is/xyz/mpv/**` |
| 获取方式 | `git clone --depth 1 --branch <branch> --filter=blob:none --sparse` + `git sparse-checkout set libraries/mpvplayer` |
| 方法 | 关键链路逐行静态审查 + media3 `Player` 契约对照 + App 侧行为反推 |
| 未覆盖 | JNI/C 层实现、`lib-exoplayer-libass` / `lib-ui-libass` 模块、实际设备播放验证（需回归测试补足） |

---

## 三、问题清单与证据

### LIB-1 `REPEAT_MODE_ONE` 下的 seek 短路分支会误伤正常 seek

**证据 1**：`core/MpvPlaybackEventHandler.java` L155-L175

```java
@Override
public void onSeek() {                       // 由 MPV_EVENT_SEEK 触发（MpvEventAdapter L136）
  if (host.hasActiveMpvFile()) {
    handleActiveFileSeek();
  }
}

private void handleActiveFileSeek() {
  if (seekController.isCommandedSeekPending()) {
    return;
  }
  if (playbackNavigator.isRepeatOneLoopSeek(host.getRepeatMode(), playbackState)) {
    eventState.setIgnoreRepeatOneBuffering(true);
    playbackState.setPositionMs(0);           // ★ 无条件把对外位置改为 0
    host.setPendingDiscontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, 0);
    host.invalidatePlayerState();
    return;                                   // ★ 不更新 seeking/loading，也不取消原生 seek
  }
  playbackState.onSeek();
  host.invalidatePlayerState();
}
```

**证据 2**：`media/MpvPlaybackNavigator.java` L44-L49

```java
public boolean isRepeatOneLoopSeek(
    @Player.RepeatMode int repeatMode, MpvPlaybackState playbackState) {
  return repeatMode == Player.REPEAT_MODE_ONE
      && playbackState.isReady()
      && playbackState.isNearEnd();      // ★ 依据「当前位置」而非「seek 目标」
}
```

**证据 3**：`core/MpvPlaybackState.java` L28、L157-L165、L441-L442

```java
private static final long END_POSITION_TOLERANCE_MS = 1000;   // 尾部容差 1s

public boolean isNearEnd() {
  return isNearEndPosition(positionMs);
}

private long getNearEndPositionMs() {
  return Util.constrainValue(durationMs - END_POSITION_TOLERANCE_MS, 0, Long.MAX_VALUE);
}
```

**现象**：开启「单集循环」后，用户在片尾 1 秒内**向回拖动**（或做任何 seek），对外位置会被瞬间改写为 0，并补发一次 `DISCONTINUITY_REASON_AUTO_TRANSITION`；而 mpv 实际仍在执行原 seek，随后 `time-pos` 回调又把位置拉回目标值 → 进度条「跳 0 再跳回」，同时触发多余的 discontinuity。

**根因**：

1. 判定用的是**当前播放位置**（`isNearEnd()`），不是**本次 seek 的目标位置**；
2. 该分支**不取消原生 seek**，只改内存状态，导致「对外状态」与「mpv 实际状态」短时间不一致；
3. App 主动「拖到片尾」的路径其实**早已在别处拦截**：`seek/MpvSeekController.seekToPositionMs()` L62-L80 中 `isEndPosition == true` 会直接走 `endedSeekHandler.endLoadedFileFromSeek()` → `maybeLoadNextAfterEnd()`，根本不会走到 `onSeek`。因此该分支**没有承担任何必要的业务职责**。

**影响**：循环模式下拖动/跳转体验异常（进度条闪跳、discontinuity 计数错误、部分 App 会误判为自动切集）。

**修复设计（推荐：删除该短路分支）**：

```java
/**
 * 处理 mpv 的 SEEK 事件。
 *
 * <p>注意：App 的「seek 到片尾」已在 {@code MpvSeekController#seekToPositionMs} 中
 * 通过 {@code isEndPosition} 拦截并走循环/结束流程，无需在此重复处理；
 * 此处只同步 seeking 状态，避免用「当前位置接近尾部」误判并篡改对外位置。</p>
 */
private void handleActiveFileSeek() {
  if (seekController.isCommandedSeekPending()) {
    return;
  }
  playbackState.onSeek();
  host.invalidatePlayerState();
}
```

配套清理：

- `MpvPlaybackNavigator.isRepeatOneLoopSeek(...)` 删除（无其它调用点）；
- `MpvPlaybackEventState.ignoreRepeatOneBuffering` 与其消费点 `MpvPropertyEventHandler.consumeIgnoredRepeatOneBuffering(...)`（L281-L289）一并评估删除：循环点已改为真实重载（`maybeLoadNextAfterEnd` → `startLoading` → `BUFFERING`），该抑制标志失去意义。

**备选（保守）**：保留分支但改为「仅当本次没有命令 seek 且位置在尾部」时生效，即在 `MpvSeekController` 记录 `lastCommandedSeekTargetMs`，`isRepeatOneLoopSeek` 改为判断目标位置 ≥ 时长。改动面比删除大，收益一致，不推荐。

**验证**：见 LIB-T1 ~ LIB-T3。

---

### LIB-2 `END_FILE` 未区分 `reason`，`REDIRECT`/`QUIT` 会被当作播放结束

**证据 1**：`core/MpvPlaybackEventHandler.java` L140-L153

```java
@Override
public void onEndFile(int reason, int error, @Nullable String errorString) {
  if (consumeExpectedEndFile()) {
    return;
  }
  boolean loadFailed = finishCurrentEndFile();          // ★ 无条件把状态置为 ENDED
  if (maybeFailEndFile(reason, error, errorString, loadFailed)) {
    return;
  }
  if (host.maybeLoadNextAfterEnd()) {
    return;
  }
  finishEndedPlayback();                                // ★ 对外派发 ENDED
}
```

**证据 2**：`nativebridge/MpvPlaybackErrorFactory.java` L34-L36

```java
public static boolean isEndFileError(int reason, int error) {
  return reason == MPV_END_FILE_REASON_ERROR || error < MPV_ERROR_SUCCESS;
}
```

**证据 3**：`is/xyz/mpv/MPVLib.java` L184-L188

```java
public static final int MPV_END_FILE_REASON_EOF = 0;
public static final int MPV_END_FILE_REASON_STOP = 2;
public static final int MPV_END_FILE_REASON_QUIT = 3;
public static final int MPV_END_FILE_REASON_ERROR = 4;
public static final int MPV_END_FILE_REASON_REDIRECT = 5;
```

**现象**：只有 `reason == ERROR(4)` 或 `error < 0` 被当作错误；`REDIRECT(5)` 与 `QUIT(3)` 会走「正常结束」路径，最终对外派发 `STATE_ENDED`。

**影响（真实可触发）**：

- 用户通过 `mpv.conf` 打开 `ytdl` / `ytdl_hook`（[MpvConfigFile.java](../../app/src/main/java/com/fongmi/android/tv/player/mpv/MpvConfigFile.java) 允许覆盖选项）时，mpv 会因解析重定向产生 `REDIRECT` 结束事件 → 库里提前派发 ENDED → 上层误判「播完」并自动切集，而实际新 URL 随后才真正开始播放 → **连播乱跳/跳集**。
- `QUIT` 场景（native 被回收、异常退出）同样会派发 ENDED，与 `onShutdown` 的语义冲突。

**修复设计**：

```java
@Override
public void onEndFile(int reason, int error, @Nullable String errorString) {
  if (consumeExpectedEndFile()) {
    return;
  }
  // 重定向不是播放结束：mpv 随后会对新地址触发 START_FILE，保持当前状态即可
  if (reason == MPVLib.MpvEndFileReason.MPV_END_FILE_REASON_REDIRECT) {
    endFileGuard.clear();
    return;
  }
  // mpv 正在销毁：交由 onShutdown 处理，避免误报 ENDED
  if (reason == MPVLib.MpvEndFileReason.MPV_END_FILE_REASON_QUIT) {
    endFileGuard.clear();
    return;
  }
  boolean loadFailed = finishCurrentEndFile();
  if (maybeFailEndFile(reason, error, errorString, loadFailed)) {
    return;
  }
  if (host.maybeLoadNextAfterEnd()) {
    return;
  }
  finishEndedPlayback();
}
```

补充：`MPV_END_FILE_REASON_STOP` 保持现有行为（正常由 `stopActiveFile()` 的 `expect()` 抵扣），避免改动引入停止流程回归。

**验证**：见 LIB-T4 ~ LIB-T6。

---

### LIB-3 `COMMAND_SEEK_TO_PREVIOUS` 可用性判定过度声明

**证据**：`core/MpvAvailableCommands.java` L55-L57

```java
boolean canSeekToPrevious =
    !timelineEmpty && (hasPreviousMediaItem || !currentMediaLive || currentMediaSeekable);
boolean canSeekToNext = !timelineEmpty && (hasNextMediaItem || currentMediaLive);
```

**问题**：`!currentMediaLive` 这一项会使**单条 VOD 且不可 seek** 的内容也声明支持 `COMMAND_SEEK_TO_PREVIOUS` 与 `COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM` 之外的「回退」能力，但此时既无上一项、也无法 seek，命令执行后什么也不会发生。

对照 media3 契约：`COMMAND_SEEK_TO_PREVIOUS` 的可用条件是「存在上一项」或「当前项为直播且可 seek」，即 `hasPreviousMediaItem || (currentMediaLive && currentMediaSeekable)`。

**影响**：TV 端「上一集/回到开头」按键在部分不可 seek 内容上显示可用、按下无反应；跨引擎（Exo ↔ MPV）命令集不一致，UI 状态机可能分支错乱。

**修复设计**：

```java
boolean canSeekToPrevious =
    !timelineEmpty && (hasPreviousMediaItem || (currentMediaLive && currentMediaSeekable));
```

`canSeekToNext` 暂不改动（直播「跳到最新」语义与是否可 seek 的组合需要单独评审），仅在本单中记录待评估。

**验证**：见 LIB-T7。

---

### LIB-4 `addSubtitle` 吞掉命令失败返回值，向调用方谎报成功

**证据**：`media/MpvSubtitleController.java` L162-L176

```java
public boolean addSubtitle(MediaItem.SubtitleConfiguration subtitle, boolean select) {
  if (!host.hasActiveFile() || host.currentMediaItem() == null || host.hasPlayerError()) {
    return false;
  }
  if (!host.isCurrentFileLoaded()) {
    pendingSubtitles = ...;
    return true;
  }
  commandDispatcher.addSubtitle(subtitle, select);   // ★ 返回值被丢弃
  return true;                                        // ★ 无条件成功
}
```

对照同类实现 `addPendingSubtitles()` L151-L160 会正确使用返回值：

```java
if (commandDispatcher.addSubtitle(subtitle, select)) {
  selected |= select;
}
```

**影响**：`MpvPlayer.addSubtitle(...)`（`MpvPlayer.java` L200-L203）向 App 返回 `true`，但 mpv 侧 `sub-add` 实际失败（URL 非法、网络不通、mpv 处于不可用状态等）→ App 侧 [MpvPlayerEngine.addSubtitle](../../app/src/main/java/com/fongmi/android/tv/player/mpv/MpvPlayerEngine.java) 认为外挂字幕已加载，UI 显示成功但画面无字幕，且不会走失败重试分支。

**修复设计**：

```java
public boolean addSubtitle(MediaItem.SubtitleConfiguration subtitle, boolean select) {
  if (!host.hasActiveFile() || host.currentMediaItem() == null || host.hasPlayerError()) {
    return false;
  }
  if (!host.isCurrentFileLoaded()) {
    pendingSubtitles = ...;
    return true;
  }
  // 如实返回 mpv 命令结果，避免上层误判字幕已挂载
  return commandDispatcher.addSubtitle(subtitle, select);
}
```

> 注意：`MpvPlayer.addSubtitle` 的调用方（App）需同步确认失败分支的 UI 提示，属联调项，不在库内改动。

**验证**：见 LIB-T8。

---

### LIB-5 「拖到片尾」结束后首次按播放被吞一次（可选修复）

**证据**：`seek/MpvEndedSeekHandler.java` L46、L50-L64、L85-L97

```java
private void completeEndedSeek() {
  ...
  skipNextDefaultPositionSeek();     // ★ seek 到片尾结束时置位
  host.releaseAudioFocus();
}

void seekWithoutLoadedFile(long positionMs, int seekCommand, boolean isEndPosition) {
  if (isEndPosition) {
    ...
    playbackState.setEnded();
    skipNextDefaultPositionSeek();   // ★ 无文件时同样置位
    return;
  }
  ...
}

private boolean consumeDefaultPositionSeek(@Player.Command int seekCommand) {
  if (!skipNextDefaultPositionSeek || seekCommand != Player.COMMAND_SEEK_TO_DEFAULT_POSITION) {
    return false;
  }
  // MediaSession maps play() in STATE_ENDED to seekToDefaultPosition() + play().
  skipNextDefaultPositionSeek = false;
  return true;                       // ★ 该次 seek 被吞掉，播放器仍停在 ENDED
}
```

**现象**：用户拖到片尾进入 ENDED 后，若上层不在 ENDED 时切集（例如已是最后一集），第一次按「播放」只会消耗掉 `skipNextDefaultPositionSeek`，播放器仍停在 ENDED；**第二次按**才会真正重播。自然播完进入 ENDED 的路径不置位该标志，因此不存在该问题——即两条 ENDED 路径行为不一致。

**影响**：仅影响「拖到片尾 + 最后一集 + 再按播放」这一组合，属低概率体验问题；App 若在 ENDED 时切集则完全无感。

**修复设计（可选，需与 App 侧确认）**：删除 `skipNextDefaultPositionSeek` 机制（含 L27 字段、L46/L56 置位、L85-L97 消费逻辑），统一为「ENDED 后的任意 seek 都按 `maybeRestartEndedMedia` 重启」，两条 ENDED 路径行为一致。

- 风险：若某个 App 依赖「ENDED 后按播放不重启」，改动会改变该行为。需在联调清单中逐项确认。
- 若不改：请在类注释中显式登记「seek-to-end 后首次 seekToDefaultPosition 会被吞一次」这一已知行为，避免后续误判为 bug。

**验证**：见 LIB-T9。

---

### LIB-6 `REPEAT_MODE_ONE` 用「重载文件」模拟循环，非无缝（改进项，非缺陷）

**证据 1**：`nativebridge/MpvPlaybackProperties.java` L83、L95-L97

```java
strings.set(PROP_LOOP_FILE, VALUE_NO);   // 每次 loadfile 都强制关闭原生循环

public void disableNativeLoop() {
  properties.setStringProperty(PROP_LOOP_FILE, VALUE_NO);
}
```

**证据 2**：`media/MpvTimelineController.java` L81-L91

```java
public boolean maybeLoadNextAfterEnd(int repeatMode) {
  int nextIndex = playbackNavigator.getNextIndexAfterEnd(repeatMode);
  if (nextIndex == C.INDEX_UNSET) {
    return false;
  }
  ...
  host.loadCurrent(checkNotNull(playlist.current()), 0);   // ★ 重新 loadfile
  return true;
}
```

**现象**：单集循环每次到片尾都会重新 `loadfile`：状态经过 `ENDED → BUFFERING → READY`，`tracks` / `artwork` / `videoState` 被清空重建，可能出现短暂黑帧与重新缓冲；而 Exo 的 `REPEAT_MODE_ONE` 是无缝循环。

**影响**：双引擎切换时用户可以感知到差异（MPV 循环有间隙）；对「连续循环播放」场景体验不佳。

**改进方向（本轮不改，需单独立项评估）**：

- 重复模式下改用 mpv 原生 `loop-file=inf`；
- 在 `MpvPropertyEventHandler` 的 `time-pos` 回调中检测「位置回绕」（从接近 duration 跳到 0），据此补发 `DISCONTINUITY_REASON_AUTO_TRANSITION` 并重置 `positionMs`；
- 注意：原生循环下 mpv **不会**产生 `END_FILE`，因此不能依赖现有 `onEndFile` 链路，需要新增回绕检测分支。

**结论**：收益（无缝）与风险（新增回绕检测、与 `endFileGuard` 交互）需权衡，建议先修 P1/P2，此项单独评审。

---

## 四、明确不建议改动的点

| 想法 | 结论 | 理由 |
| --- | --- | --- |
| 在库侧让 `REPEAT_MODE_ONE` 也产生 `STATE_ENDED` | **禁止** | 违反 media3 契约；上层会在循环时收到「结束」并自动切集，产生双播/跳集 |
| 在库侧为 MPV 补发 `STATE_ENDED`（`ForwardingPlayer` 或监听器） | **禁止** | 库已正确上报；补发会造成双发；且无法覆盖重复模式（补发必须自我抑制） |
| 设置 `keep-open=yes` 规避结束 | **禁止** | 会永久停在末帧、状态永不 ENDED，问题更严重；且库内全无该选项 |
| 放宽 / 移除 `MpvEndFileGuard` | **不建议** | 该守卫拦截「程序化结束」，移除会导致切集时误报 ENDED |
| 修改 `MpvPlaybackState.onEndFile()` 的 `STATE_ENDED` 置位 | **不建议** | 这是库正确上报 ENDED 的唯一来源，改动风险极高 |

---

## 五、修复实施计划

| 顺序 | 编号 | 改动文件 | 风险 | 前置 |
| --- | --- | --- | --- | --- |
| 1 | LIB-4 | `media/MpvSubtitleController.java` | 极低（仅返回值） | 无 |
| 2 | LIB-3 | `core/MpvAvailableCommands.java` | 低（命令集收敛） | 需回归 TV 按键与通知栏 |
| 3 | LIB-2 | `core/MpvPlaybackEventHandler.java` | 中（结束事件分叉） | 需覆盖 ytdl/重定向场景 |
| 4 | LIB-1 | `core/MpvPlaybackEventHandler.java`、`media/MpvPlaybackNavigator.java`、`core/MpvPlaybackEventState.java`、`core/MpvPropertyEventHandler.java` | 中（seek/循环状态） | 需覆盖循环+拖动 |
| 5 | LIB-5 | `seek/MpvEndedSeekHandler.java` | 中（播放键行为） | 需与 App 确认 |
| 6 | LIB-6 | `nativebridge/MpvPlaybackProperties.java` 等 | 高（原生循环改造） | 单独立项 |

**实施约束**：

- 每个问题**独立提交**，便于单独回滚与 bisect；
- 不改变任何公有 API 签名（`addSubtitle` 返回值语义修正除外，属 bugfix）；
- 每次提交附带对应测试用例编号（见第六节）。

---

## 六、回归测试矩阵

| 用例 | 场景 | 期望 |
| --- | --- | --- |
| LIB-T1 | `REPEAT_MODE_ONE`，在片尾前 0.5s 向回拖动 | 位置直接到目标点，**不出现**跳到 0 或多余 discontinuity |
| LIB-T2 | `REPEAT_MODE_ONE`，自然播放到片尾 | 循环重播（`BUFFERING → READY`），不产生对外 ENDED |
| LIB-T3 | `REPEAT_MODE_ONE`，拖到片尾 | 循环回到 0 重播，不产生对外 ENDED |
| LIB-T4 | 开启 `ytdl_hook`，播放需要重定向的地址 | **不产生** ENDED；重定向后正常播放 |
| LIB-T5 | 播放中强制销毁 native（模拟异常退出） | 不产生伪 ENDED；走 `onPlayerError` 或 shutdown 分支 |
| LIB-T6 | `repeatMode=OFF` 自然播完 | 恰好一次 ENDED（基线不回退，对应 App 侧 T1） |
| LIB-T7 | 单条 VOD 且不可 seek | `COMMAND_SEEK_TO_PREVIOUS` 不可用；多集剧集时可用 |
| LIB-T8 | 挂载一个必失败的非法字幕 URL | `addSubtitle` 返回 `false`；App 能走失败提示 |
| LIB-T9 | 拖到片尾 → 立即按播放（最后一集） | 第一次按键即重播（若采纳 LIB-5）；否则行为与文档登记一致 |
| LIB-T10 | `REPEAT_MODE_ALL`，多集时间线播完最后一集 | 回到第 1 集（现有行为不回退） |

---

## 七、发布与联调建议

1. **先修 App 侧，再升级 aar**：App 侧「重复状态粘连」修复与库升级是两个独立变量，混在一起会导致问题归因困难。建议顺序：App 修复 → 验证自动连播 → 库升级 → 再回归。
2. **aar 产出**：`libraries/mpvplayer` 构建产物替换 App 的 `app/libs/*.aar`（注意 `app/libs/.gitignore` 中 `lib-*.aar` 已被忽略，需同步发布流程）。
3. **版本标识**：建议在 `MpvPlayer` 中暴露一个 `getLibraryVersion()`（或在 `MpvLibrary` 常量中维护），便于线上日志区分 aar 版本；当前版本无统一标识，排障困难。
4. **上游同步**：本分支为 fork，建议每处修复以独立 patch / commit 形式保留，并在 commit message 中记录上游对应位置，便于后续 rebase 到新版 `media`。
5. **诊断日志**：建议在 `onEndFile` 入口统一打印 `reason / error / loadFailed / repeatMode / currentIndex`，本次 LIB-2 的修复效果强依赖该日志确认。

---

## 八、涉及文件清单

**需修改**

| 文件 | 关联问题 |
| --- | --- |
| `core/MpvPlaybackEventHandler.java` | LIB-1、LIB-2 |
| `media/MpvPlaybackNavigator.java` | LIB-1（删除无用方法） |
| `core/MpvPlaybackEventState.java` | LIB-1（清理循环缓冲抑制标志） |
| `core/MpvPropertyEventHandler.java` | LIB-1（清理抑制标志消费点） |
| `core/MpvAvailableCommands.java` | LIB-3 |
| `media/MpvSubtitleController.java` | LIB-4 |
| `seek/MpvEndedSeekHandler.java` | LIB-5（可选） |
| `nativebridge/MpvPlaybackProperties.java` | LIB-6（改进项） |

**只读审查（本次不改）**

- `core/MpvPlaybackState.java`
- `core/MpvStateBuilder.java`
- `media/MpvTimelineController.java`
- `media/MpvMediaLoader.java`
- `media/MpvEndFileGuard.java`
- `nativebridge/MpvPlaybackErrorFactory.java`
- `nativebridge/MpvEventAdapter.java`
- `is/xyz/mpv/MPVLib.java`
- `MpvPlayer.java`

**App 侧联调文件（另一工程）**

- [MpvPlayerEngine.java](../../app/src/main/java/com/fongmi/android/tv/player/mpv/MpvPlayerEngine.java)（LIB-4 失败分支处理）
- [PlayerManager.java](../../app/src/main/java/com/fongmi/android/tv/player/PlayerManager.java)（重复状态粘连，见关联文档）
