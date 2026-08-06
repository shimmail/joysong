import { useState, useEffect } from 'react';
import { Editor, Toolbar } from '@wangeditor/editor-for-react';
import type { IDomEditor, IEditorConfig, IToolbarConfig } from '@wangeditor/editor';
import '@wangeditor/editor/dist/css/style.css';

interface RichTextEditorProps {
  value?: string;
  onChange?: (value: string) => void;
}

export default function RichTextEditor({ value, onChange }: RichTextEditorProps) {
  const [editor, setEditor] = useState<IDomEditor | null>(null);

  useEffect(() => {
    return () => {
      if (editor) {
        editor.destroy();
        setEditor(null);
      }
    };
  }, [editor]);

  const toolbarConfig: Partial<IToolbarConfig> = {
    toolbarKeys: [
      'bold',
      'italic',
      'underline',
      'through',
      '|',
      'headerSelect',
      'blockquote',
      '|',
      'bulletedList',
      'numberedList',
      'todo',
      '|',
      'insertLink',
      'insertImage',
      '|',
      'undo',
      'redo',
    ],
  };

  const editorConfig: Partial<IEditorConfig> = {
    placeholder: '请输入正文内容...',
  };

  return (
    <div style={{ border: '1px solid #d9d9d9', borderRadius: 6 }}>
      <Toolbar
        editor={editor}
        defaultConfig={toolbarConfig}
        mode="default"
        style={{ borderBottom: '1px solid #d9d9d9' }}
      />
      <Editor
        defaultConfig={editorConfig}
        value={value}
        onCreated={setEditor}
        onChange={(editor) => onChange?.(editor.getHtml())}
        mode="default"
        style={{ height: 400, overflowY: 'hidden' }}
      />
    </div>
  );
}
