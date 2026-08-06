import { useEffect, useRef, useState, useCallback } from 'react';
import { Input, Button, Avatar, Badge, List, Spin, Empty, message } from 'antd';
import { SendOutlined, CustomerServiceOutlined, SearchOutlined, TranslationOutlined, UserOutlined } from '@ant-design/icons';
import api, { getApiErrorMessage, getData } from '../api';

interface Conversation {
  id: string;
  userId: string;
  userNickname: string;
  userAvatar: string;
  lastMessage: string | null;
  lastMessageAt: string | null;
  unreadCount: number;
}

interface ChatMessage {
  id: string;
  senderId: string;
  senderName: string;
  content: string;
  messageType: string;
  isRead: boolean;
  createdAt: string;
}

interface TranslationResponse {
  translatedText: string;
}

interface MessageTranslation {
  text?: string;
  loading: boolean;
  visible: boolean;
}

const CS_ADMIN = 'CS_ADMIN';

export default function CustomerServicePage() {
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [selectedConv, setSelectedConv] = useState<Conversation | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [inputText, setInputText] = useState('');
  const [keyword, setKeyword] = useState('');
  const [convLoading, setConvLoading] = useState(false);
  const [msgLoading, setMsgLoading] = useState(false);
  const [sending, setSending] = useState(false);
  const [messageTranslations, setMessageTranslations] = useState<Record<string, MessageTranslation>>({});
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  // 加载会话列表
  const fetchConversations = useCallback(async (kw?: string) => {
    setConvLoading(true);
    try {
      const params = kw ? { keyword: kw } : {};
      const res = await api.get('/admin/cs/conversations', { params });
      setConversations(getData<Conversation[]>(res));
    } catch {
      // 静默失败
    } finally {
      setConvLoading(false);
    }
  }, []);

  // 加载消息
  const fetchMessages = useCallback(async (convId: string) => {
    setMsgLoading(true);
    try {
      const res = await api.get(`/admin/cs/conversations/${convId}/messages`, { params: { limit: 50 } });
      const data = getData<ChatMessage[]>(res);
      // API 返回降序，翻转为升序显示
      setMessages(data.slice().reverse());
    } catch {
      // 静默失败
    } finally {
      setMsgLoading(false);
    }
  }, []);

  // 初始加载 + 轮询
  useEffect(() => {
    fetchConversations();
    timerRef.current = setInterval(() => {
      fetchConversations(keyword || undefined);
    }, 10000);
    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
    };
  }, [fetchConversations, keyword]);

  // 选中会话
  const handleSelectConv = async (conv: Conversation) => {
    setSelectedConv(conv);
    await fetchMessages(conv.id);
    // 标记已读
    try {
      await api.put(`/admin/cs/conversations/${conv.id}/read`);
      // 更新本地未读数
      setConversations(prev => prev.map(c => c.id === conv.id ? { ...c, unreadCount: 0 } : c));
    } catch {
      // 静默失败
    }
  };

  // 发送消息
  const handleSend = async () => {
    if (!inputText.trim() || !selectedConv) return;
    setSending(true);
    try {
      await api.post(`/admin/cs/conversations/${selectedConv.id}/messages`, { content: inputText.trim() });
      setInputText('');
      await fetchMessages(selectedConv.id);
      fetchConversations(keyword || undefined);
    } catch {
      message.error('发送失败，请重试');
    } finally {
      setSending(false);
    }
  };

  const handleTranslate = async (chatMessage: ChatMessage) => {
    const current = messageTranslations[chatMessage.id];
    if (current?.text) {
      setMessageTranslations(prev => ({
        ...prev,
        [chatMessage.id]: { ...prev[chatMessage.id], visible: !prev[chatMessage.id].visible },
      }));
      return;
    }

    setMessageTranslations(prev => ({
      ...prev,
      [chatMessage.id]: { loading: true, visible: true },
    }));
    try {
      const res = await api.post('/translations', {
        text: chatMessage.content,
        targetLanguage: 'zh-CN',
        contentType: 'message',
      });
      const result = getData<TranslationResponse>(res);
      if (!result?.translatedText) throw new Error('翻译服务未返回译文');
      setMessageTranslations(prev => ({
        ...prev,
        [chatMessage.id]: { text: result.translatedText, loading: false, visible: true },
      }));
    } catch (error) {
      setMessageTranslations(prev => ({
        ...prev,
        [chatMessage.id]: { loading: false, visible: false },
      }));
      message.error(getApiErrorMessage(error, '翻译失败，请稍后重试'));
    }
  };

  // 键盘事件：回车发送，Shift+回车换行
  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  // 自动滚动到底部
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  // 搜索
  const handleSearch = (value: string) => {
    setKeyword(value);
    fetchConversations(value || undefined);
  };

  const formatTime = (timeStr: string | null) => {
    if (!timeStr) return '';
    try {
      const d = new Date(timeStr);
      const now = new Date();
      const isToday = d.toDateString() === now.toDateString();
      if (isToday) {
        return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
      }
      return d.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
    } catch {
      return timeStr;
    }
  };

  return (
    <div style={{ display: 'flex', height: 'calc(100vh - 112px)', background: '#fff', borderRadius: 8, overflow: 'hidden', border: '1px solid #f0f0f0' }}>
      {/* 左侧会话列表 */}
      <div style={{ width: 320, borderRight: '1px solid #f0f0f0', display: 'flex', flexDirection: 'column' }}>
        <div style={{ padding: '12px 12px 8px' }}>
          <Input.Search
            placeholder="搜索用户昵称"
            allowClear
            onSearch={handleSearch}
            prefix={<SearchOutlined style={{ color: '#bfbfbf' }} />}
          />
        </div>
        <div style={{ flex: 1, overflowY: 'auto' }}>
          <Spin spinning={convLoading}>
            {conversations.length === 0 && !convLoading ? (
              <Empty description="暂无会话" style={{ marginTop: 60 }} />
            ) : (
              <List
                dataSource={conversations}
                renderItem={item => (
                  <List.Item
                    onClick={() => handleSelectConv(item)}
                    style={{
                      padding: '10px 12px',
                      cursor: 'pointer',
                      background: selectedConv?.id === item.id ? '#e6f4ff' : 'transparent',
                      borderLeft: selectedConv?.id === item.id ? '3px solid #1677ff' : '3px solid transparent',
                    }}
                  >
                    <div style={{ display: 'flex', alignItems: 'center', width: '100%', gap: 10 }}>
                      <Badge count={item.unreadCount} size="small" offset={[-4, 4]}>
                        <Avatar src={item.userAvatar || undefined} icon={<UserOutlined />} size={40} />
                      </Badge>
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                          <span style={{ fontWeight: 500, fontSize: 14, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                            {item.userNickname}
                          </span>
                          <span style={{ fontSize: 12, color: '#999', flexShrink: 0, marginLeft: 4 }}>
                            {formatTime(item.lastMessageAt)}
                          </span>
                        </div>
                        <div style={{ fontSize: 13, color: '#999', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', marginTop: 2 }}>
                          {item.lastMessage || '暂无消息'}
                        </div>
                      </div>
                    </div>
                  </List.Item>
                )}
              />
            )}
          </Spin>
        </div>
      </div>

      {/* 右侧消息区 */}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
        {!selectedConv ? (
          <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#999' }}>
            <Empty description="请选择一个会话开始对话" />
          </div>
        ) : (
          <>
            {/* 顶部用户信息 */}
            <div style={{ padding: '12px 16px', borderBottom: '1px solid #f0f0f0', display: 'flex', alignItems: 'center', gap: 10 }}>
              <Avatar src={selectedConv.userAvatar || undefined} icon={<UserOutlined />} size={32} />
              <span style={{ fontWeight: 500, fontSize: 15 }}>{selectedConv.userNickname}</span>
              <CustomerServiceOutlined style={{ color: '#1677ff', marginLeft: 4 }} />
            </div>

            {/* 消息列表 */}
            <div style={{ flex: 1, overflowY: 'auto', padding: '16px 20px', background: '#fafafa' }}>
              <Spin spinning={msgLoading}>
                {messages.length === 0 && !msgLoading ? (
                  <Empty description="暂无消息记录" style={{ marginTop: 60 }} />
                ) : (
                  messages.map(msg => {
                    const isOwnMessage = msg.senderId === CS_ADMIN;
                    const isIncomingText = !isOwnMessage && msg.messageType === 'TEXT' && Boolean(msg.content.trim());
                    const translation = messageTranslations[msg.id];
                    return (
                      <div key={msg.id} style={{ display: 'flex', justifyContent: isOwnMessage ? 'flex-end' : 'flex-start', marginBottom: 16 }}>
                        <div style={{ maxWidth: '75%' }}>
                          <div style={{ fontSize: 12, color: '#999', marginBottom: 4, textAlign: isOwnMessage ? 'right' : 'left' }}>
                            {isOwnMessage ? '平台客服' : msg.senderName}
                            <span style={{ marginLeft: 8 }}>{formatTime(msg.createdAt)}</span>
                          </div>
                          <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: isOwnMessage ? 'flex-end' : 'flex-start', gap: 6 }}>
                            <div style={{
                              padding: '10px 14px',
                              borderRadius: isOwnMessage ? '12px 2px 12px 12px' : '2px 12px 12px 12px',
                              background: isOwnMessage ? '#1677ff' : '#fff',
                              color: isOwnMessage ? '#fff' : '#333',
                              fontSize: 14,
                              lineHeight: 1.6,
                              wordBreak: 'break-word',
                              whiteSpace: 'pre-wrap',
                              boxShadow: '0 1px 2px rgba(0,0,0,0.06)',
                            }}>
                              {msg.messageType === 'IMAGE' ? (
                                <img
                                  src={msg.content}
                                  alt="聊天图片"
                                  style={{ display: 'block', maxWidth: 240, maxHeight: 240, borderRadius: 8, cursor: 'pointer' }}
                                  onClick={() => window.open(msg.content, '_blank', 'noopener,noreferrer')}
                                />
                              ) : msg.content}
                            </div>
                            {isIncomingText && (
                              <Button
                                type="text"
                                size="small"
                                icon={<TranslationOutlined />}
                                loading={translation?.loading}
                                onClick={() => void handleTranslate(msg)}
                                style={{ flexShrink: 0, color: '#7a7078', paddingInline: 6 }}
                                aria-label={translation?.text && translation.visible ? '收起译文' : '翻译消息'}
                              >
                                {translation?.text && translation.visible ? '收起' : '翻译'}
                              </Button>
                            )}
                          </div>
                          {translation?.text && translation.visible && (
                            <div style={{
                              marginTop: 6,
                              padding: '8px 12px',
                              borderLeft: '3px solid #e8577b',
                              borderRadius: 6,
                              background: '#fff6f8',
                              color: '#574e55',
                              fontSize: 13,
                              lineHeight: 1.6,
                              wordBreak: 'break-word',
                              whiteSpace: 'pre-wrap',
                            }}>
                              <span style={{ display: 'block', marginBottom: 2, color: '#b54a68', fontSize: 11, fontWeight: 600 }}>简体中文译文</span>
                              {translation.text}
                            </div>
                          )}
                        </div>
                      </div>
                    );
                  })
                )}
                <div ref={messagesEndRef} />
              </Spin>
            </div>

            {/* 底部输入区 */}
            <div style={{ padding: '12px 16px', borderTop: '1px solid #f0f0f0', display: 'flex', gap: 8, alignItems: 'flex-end' }}>
              <Input.TextArea
                value={inputText}
                onChange={e => setInputText(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder="输入消息，回车发送，Shift+回车换行"
                autoSize={{ minRows: 1, maxRows: 4 }}
                style={{ flex: 1 }}
              />
              <Button
                type="primary"
                icon={<SendOutlined />}
                onClick={handleSend}
                loading={sending}
                disabled={!inputText.trim()}
              >
                发送
              </Button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
