# AI Agent Enabled by Default

## Goal

Agent chat is enabled without requiring an `AI_AGENT_ENABLED` environment
variable, while operators retain the ability to disable it explicitly.

## Design

- Keep the `enabled` field in `AiAgentProperties` and change its default to
  `true`.
- Keep the availability guard and `AGENT_DISABLED` response for explicitly
  disabled deployments.
- Change `ai-agent.enabled` to `${AI_AGENT_ENABLED:true}` so the environment
  variable is optional and only needed to override the default.
- Keep provider credentials and routing configurable through `OPENAI_API_KEY`,
  `OPENAI_BASE_URL`, `AI_AGENT_MODEL`, and the existing proxy settings.
- Production provider configuration remains required when the Agent is enabled.
- An explicitly disabled production deployment may start without provider
  credentials and returns `AGENT_DISABLED` for Agent chat requests.

## Error Handling

Missing or invalid provider configuration fails at the provider configuration
boundary when the Agent is enabled. Explicitly disabled deployments retain the
existing `AGENT_DISABLED` service-unavailable response.

## Testing

- Add a focused test proving the typed property defaults to enabled.
- Update configuration tests to prove an explicit `false` still disables the
  Agent and permits production startup without provider credentials.
- Run the smallest relevant backend test classes first, followed by at most one
  broader backend test run if warranted.

## Scope

No Flutter behavior, API routes, prompts, provider selection, runtime guard, or
database schema is changed.
