# AI Agent Enabled by Default Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable Agent chat by default while preserving `AI_AGENT_ENABLED=false` as an explicit operational override.

**Architecture:** Keep the existing typed `enabled` property, runtime availability guard, and `AGENT_DISABLED` response. Change only the property and YAML defaults from false to true, with focused tests proving both default enablement and explicit disablement.

**Tech Stack:** Kotlin, Spring Boot configuration properties, JUnit 5, Gradle.

## Global Constraints

- `AiAgentProperties.enabled` defaults to `true`.
- `ai-agent.enabled` resolves from `${AI_AGENT_ENABLED:true}`.
- Explicit `AI_AGENT_ENABLED=false` continues to disable Agent chat.
- Production provider credentials remain required only when Agent chat is enabled.
- Do not change Flutter behavior, API routes, prompts, provider selection, runtime guard, or database schema.
- Preserve unrelated working-tree changes.

---

### Task 1: Default Agent chat to enabled

**Files:**
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/config/AiAgentPropertiesTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/config/AiAgentProfileStartupTest.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/config/AiAgentProperties.kt`
- Modify: `joysong-server/src/main/resources/application.yml`

**Interfaces:**
- Consumes: optional `AI_AGENT_ENABLED` environment variable through Spring configuration.
- Produces: `AiAgentProperties.enabled == true` when no override exists, and `false` when explicitly configured false.

- [ ] **Step 1: Write failing default-value tests**

  Add this test to `AiAgentPropertiesTest`:

  ```kotlin
  @Test
  fun `AI agent is enabled by default`() {
      assertTrue(AiAgentProperties().enabled)
  }
  ```

  Keep the existing binding test for `ai-agent.enabled=true`. Keep the production-profile test that configures `ai-agent.enabled=false` and verifies startup without provider credentials; it proves explicit disablement remains supported.

- [ ] **Step 2: Run focused tests and confirm RED**

  Run:

  ```powershell
  $gradleHome = Join-Path $PWD '..\.tmp\gradle-user-home-codex'
  New-Item -ItemType Directory -Force -Path $gradleHome | Out-Null
  $env:GRADLE_USER_HOME = $gradleHome
  .\gradlew.bat test --tests com.joysong.server.config.AiAgentPropertiesTest --tests com.joysong.server.config.AiAgentProfileStartupTest
  ```

  Expected: `AI agent is enabled by default` fails because the current Kotlin default is `false`.

- [ ] **Step 3: Implement the new defaults**

  Change `AiAgentProperties` to:

  ```kotlin
  var enabled: Boolean = true,
  ```

  Change `application.yml` to:

  ```yaml
  ai-agent:
    enabled: ${AI_AGENT_ENABLED:true}
  ```

  Do not modify `AiAgentAvailabilityGuard`, `AgentChatException`, `AgentChatExceptionHandler`, or `ConfigValidator`.

- [ ] **Step 4: Run focused tests and confirm GREEN**

  Run the command from Step 2. Expected: both test classes pass.

- [ ] **Step 5: Verify the exact configuration surface**

  Run:

  ```powershell
  Get-ChildItem joysong-server/src -Recurse -File | Select-String -Pattern 'AI_AGENT_ENABLED|ai-agent.enabled|var enabled: Boolean'
  ```

  Expected: the application default is `true`, the typed default is `true`, and existing tests retain explicit `false` coverage.

- [ ] **Step 6: Commit the implementation**

  Stage only the four task files and commit with:

  ```powershell
  git commit -m "fix: enable AI agent by default"
  ```
