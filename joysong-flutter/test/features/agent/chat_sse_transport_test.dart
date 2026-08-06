import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/agent/data/chat_sse_transport.dart';
import 'package:joysong_flutter/features/agent/domain/agent_repository.dart';

void main() {
  test('decodes delta, done and error SSE events', () {
    final decoder = AgentSseDecoder();
    final events = <ChatStreamEvent>[];

    for (final line in [
      'event:delta',
      'data:{"content":"你"}',
      '',
      'event:done',
      'data:{"message":{"id":"m1","sessionId":"s1","role":"ASSISTANT","content":"你好","createdAt":"2026-08-06T10:00:00"},"intent":"GENERAL_CHAT","nextAction":"NONE"}',
      '',
      'event:error',
      'data:{"message":"模型不可用"}',
      '',
    ]) {
      events.addAll(decoder.addLine(line));
    }

    expect(events, hasLength(3));
    expect(events[0].type, ChatStreamEventType.delta);
    expect(events[0].content, '你');
    expect(events[1].type, ChatStreamEventType.done);
    expect(events[1].turn?.message.content, '你好');
    expect(events[2].type, ChatStreamEventType.error);
    expect(events[2].message, '模型不可用');
  });

  test('flushes a final event even without a trailing blank line', () {
    final decoder = AgentSseDecoder();
    decoder.addLine('event:delta');
    decoder.addLine('data:{"content":"保留"}');

    final events = decoder.close();

    expect(events.single.content, '保留');
  });
}
