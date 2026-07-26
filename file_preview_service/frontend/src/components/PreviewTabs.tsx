import { Alert, Button } from 'antd';
import { CloseOutlined } from '@ant-design/icons';
import type { FileEntry } from '../types';
import { humanBytes } from '../utils';

interface Props {
  tabs: FileEntry[];
  activePath?: string;
  cacheBytes: number;
  warnBytes: number;
  onSelect: (f: FileEntry) => void;
  onClose: (path: string) => void;
  onCloseOldest: () => void;
}

// 文件预览标签行（Notepad++ 风格）：按打开顺序排列，点击切换激活，× 关闭；
// cacheBytes 超过 warnBytes 时在标签行下方提示（仅提醒不强制清理）。
export default function PreviewTabs({ tabs, activePath, cacheBytes, warnBytes, onSelect, onClose, onCloseOldest }: Props) {
  if (tabs.length === 0) return null;
  return (
    <>
      <div className="fp-tabs">
        <div className="fp-tabs__strip">
          {tabs.map((t) => (
            <div
              key={t.path}
              className={`fp-tabs__item${t.path === activePath ? ' fp-tabs__item--active' : ''}`}
              title={t.path}
              onClick={() => onSelect(t)}
            >
              <span className="fp-tabs__name">{t.path.split('/').filter(Boolean).pop() || t.path}</span>
              <CloseOutlined
                className="fp-tabs__close"
                onClick={(e) => {
                  e.stopPropagation();
                  onClose(t.path);
                }}
              />
            </div>
          ))}
        </div>
        <span className="fp-tabs__meter fp-mono">缓存 {humanBytes(cacheBytes)}</span>
      </div>
      {cacheBytes > warnBytes && (
        <Alert
          className="fp-tabs__alert"
          type="warning"
          showIcon
          message={`预览缓存已达 ${humanBytes(cacheBytes)}，建议关闭不用的标签页释放内存`}
          action={
            <Button size="small" onClick={onCloseOldest}>
              关闭最旧标签
            </Button>
          }
        />
      )}
    </>
  );
}
