# AI Agent Always Enabled

## Goal

Agent chat is always available at the application level. It must not require an
`AI_AGENT_ENABLED` environment variable or an `ai-agent.enabled` configuration
property.

## Design

- Remove the `enabled` field from `AiAgentProperties`.
- Remove the availability guard and its calls so every authenticated agent-chat
  request proceeds to the normal orchestration flow.
- Remove `ai-agent.enabled` from application configuration and all references to
  `AI_AGENT_ENABLED`.
- Keep provider credentials and routing configurable through `OPENAI_API_KEY`,
  `OPENAI_BASE_URL`, `AI_AGENT_MODEL`, and the existing proxy settings.
- When required provider configuration is missing, return the existing explicit
  provider-configuration error instead of `AGENT_DISABLED`.
- Remove `AGENT_DISABLED` handling if it has no remaining producers.

## Error Handling

Missing or invalid provider configuration fails at the provider configuration
boundary with a specific server-side error. It must not be reported as a feature
toggle or authentication failure.

## Testing

- Add or update a focused test proving agent chat is not gated by an enabled flag.
- Update configuration tests to prove the environment toggle no longer exists.
- Run the smallest relevant backend test classes first, followed by at most one
  broader backend test run if warranted.

## Scope

No Flutter behavior, API routes, prompts, provider selection, or database schema
is changed.
