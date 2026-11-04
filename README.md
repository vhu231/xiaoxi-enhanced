# 小溪 BYOK 增强

面向魅族小溪 **13.1.1（versionCode 13001001）**的 LSPosed 模块，使用传统 Xposed API 82。模块将自定义 OpenAI-compatible Chat Completions 服务接入原应用，并补充提示词、上下文、技能、语音、定时任务和请求轨迹功能。

## 功能

- **模型与生成**：按 API 地址隔离配置，支持模型 ID、上下文窗口、输出预算、扩展 JSON，以及跟随聊天或独立配置的图片理解服务。
- **思考等级**：按模型协议提供可用选项。DeepSeek 提供 Default、low、high、max；`reasoning_effort` 提供 Default、low、medium、high；`thinking_type` 和 `enable_thinking` 提供 Default 和开关。选择按会话和模型保存，下一轮请求生效。Default 不发送模块指定的思考字段。
- **Prompt 与上下文**：编辑基础提示词和有序预设规则，预览应用前后差异；支持消息修改、排除、追加、保留策略、摘要和长期记忆管理。
- **Skill 与 MCP**：导入、编辑和导出 Skill；配置 HTTP MCP 连接器、工具暴露状态和手动调用。
- **语音与 TTS**：接入原生播放队列，支持原生语音、Vocu 和自定义 HTTP API，以及角色、长文、多角色生成、字幕、解码后和处理后的 WAV 导出。
- **定时任务**：沿用原生 Scheduler，支持 Agent、原生工具、MCP、TTS 和通知步骤，配置任务及步骤级模型、工具白名单、重叠和错过执行策略。
- **观测与界面**：查看请求、思考、工具调用和用量轨迹。增强设置使用独立页面栈，处理系统返回、状态栏、键盘、草稿和滚动位置。

DeepSeek 的 `medium` 和 `xhigh` 是 `high` 的映射，不作为独立档位展示；`max` 是独立强度。参考 [DeepSeek Thinking Mode 文档](https://api-docs.deepseek.com/guides/thinking_mode/)。

## 运行环境

- LSPosed，Xposed API 82 (Legacy)。
- 魅族小溪 13.1.1（versionCode 13001001）。

## 安装与配置

1. 在 LSPosed 中启用模块，并将作用域设为 `com.meizu.voiceassistant`。
2. 重新打开小溪，在原应用的模型设置中配置 API 地址、模型和密钥。
3. 打开“设置 → 开发者工具下方 → 增强设置”。
4. 在聊天输入框的“思考等级”中选择当前模型支持的选项。选择从下一轮请求开始生效。

## 目录说明

```text
app/src/main/          模块 Java、资源、清单、提示词和许可证
app/src/compileOnly/   仅供编译的 Kotlin 协程 stub，不进入 APK
lib/                   Xposed API 82 编译依赖
tests/                 音频处理 JVM 测试
tools/audit_host.py    宿主 DEX 字面量 ABI 检查
build.ps1              标准构建脚本
```

