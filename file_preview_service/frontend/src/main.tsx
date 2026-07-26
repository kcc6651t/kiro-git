import React from 'react';
import ReactDOM from 'react-dom/client';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { loader } from '@monaco-editor/react';
import App from './App';
import { appTheme } from './theme';
import './index.css';

// 从同源加载 Monaco 资源（构建时已拷贝到 /monaco/vs），避免默认走 CDN，
// 使内网/离线部署也能正常渲染预览编辑器。
loader.config({ paths: { vs: '/monaco/vs' } });

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider locale={zhCN} theme={appTheme}>
      <App />
    </ConfigProvider>
  </React.StrictMode>,
);
