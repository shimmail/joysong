import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import RichTextEditor from './RichTextEditor';

vi.mock('@wangeditor/editor-for-react', () => ({
  Toolbar: ({ defaultConfig }: { defaultConfig: { toolbarKeys?: string[] } }) => (
    <output data-testid="toolbar-config">{JSON.stringify(defaultConfig.toolbarKeys)}</output>
  ),
  Editor: ({ defaultConfig, style }: {
    defaultConfig: { placeholder?: string };
    style: { height?: number };
  }) => (
    <output data-testid="editor-config">
      {JSON.stringify({ placeholder: defaultConfig.placeholder, height: style.height })}
    </output>
  ),
}));

describe('RichTextEditor', () => {
  it('passes restricted toolbar keys, placeholder, and height to WangEditor while retaining article defaults', () => {
    const legalToolbarKeys = ['headerSelect', 'bold', 'italic', 'underline', 'blockquote', 'bulletedList', 'numberedList', 'insertLink', 'undo', 'redo'];
    const { rerender } = render(
      <RichTextEditor toolbarKeys={legalToolbarKeys} placeholder="请输入隐私政策" height={320} />,
    );

    expect(screen.getByTestId('toolbar-config')).toHaveTextContent(JSON.stringify(legalToolbarKeys));
    expect(screen.getByTestId('editor-config')).toHaveTextContent(JSON.stringify({ placeholder: '请输入隐私政策', height: 320 }));

    rerender(<RichTextEditor />);

    expect(screen.getByTestId('toolbar-config')).toHaveTextContent(JSON.stringify([
      'bold', 'italic', 'underline', 'through', '|', 'headerSelect', 'blockquote', '|',
      'bulletedList', 'numberedList', 'todo', '|', 'insertLink', 'insertImage', '|', 'undo', 'redo',
    ]));
    expect(screen.getByTestId('editor-config')).toHaveTextContent(JSON.stringify({ placeholder: '请输入正文内容...', height: 400 }));
  });
});
