import { theme as antdTheme } from 'antd';
import type { ThemeConfig } from 'antd';

/**
 * 全站 Ant Design 主题 —— antd 主题的唯一入口，组件里不得再散落主题相关字面量。
 * 取值必须与 src/styles/tokens.css 的 --fp-* 变量保持一致（tokens.css 是设计 token 的事实来源）。
 */
export const appTheme: ThemeConfig = {
  // 默认（舒适）密度：信息密集但保证可读性
  algorithm: antdTheme.defaultAlgorithm,
  token: {
    colorPrimary: '#1677ff',
    colorSuccess: '#52c41a',
    colorWarning: '#faad14',
    colorError: '#ff4d4f',
    colorText: '#1f2937',
    colorTextSecondary: '#6b7280',
    colorBorder: '#eaecef',
    colorBorderSecondary: '#eaecef',
    colorBgLayout: '#f0f2f5',
    borderRadius: 8,
    borderRadiusSM: 6,
    borderRadiusLG: 12,
    fontSize: 14,
    fontFamily:
      "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'PingFang SC', 'Microsoft YaHei', sans-serif",
    controlHeight: 34,
    controlHeightSM: 28,
    boxShadow: '0 1px 2px rgba(15, 23, 42, 0.04), 0 1px 3px rgba(15, 23, 42, 0.06)',
    boxShadowSecondary: '0 6px 24px rgba(15, 23, 42, 0.12)',
    boxShadowTertiary: '0 16px 48px rgba(15, 23, 42, 0.16)',
  },
  components: {
    Layout: {
      headerBg: '#0b1526',
      headerHeight: 52,
      headerPadding: '0 20px',
      bodyBg: '#f0f2f5',
    },
    Button: {
      primaryShadow: '0 2px 8px rgba(22, 119, 255, 0.28)',
      defaultShadow: 'none',
      fontWeight: 500,
    },
    Table: {
      headerBg: '#fafbfc',
      headerColor: '#1f2937',
      headerSplitColor: 'transparent',
      rowHoverBg: '#f3f6fa',
      cellPaddingBlockSM: 9,
      cellPaddingInlineSM: 12,
      fontSize: 14,
    },
    Card: {
      paddingLG: 20,
    },
    Tag: {
      borderRadiusSM: 6,
    },
    Modal: {
      titleFontSize: 16,
    },
    Segmented: {
      trackBg: '#f0f2f5',
    },
    List: {
      fontSize: 14,
    },
  },
};
