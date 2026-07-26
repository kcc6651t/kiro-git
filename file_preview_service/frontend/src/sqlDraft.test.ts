import { describe, it, expect } from 'vitest';
import { serializeDraft, parseDraft, nextTabSeq, type DraftTab } from './sqlDraft';

const tabs: DraftTab[] = [
  { key: 'tab-1', title: '查询 1', sql: 'SELECT 1', database: 'orders', maxRows: 1000 },
  { key: 'tab-3', title: '查询 2', sql: '', maxRows: 500 },
];

describe('sqlDraft', () => {
  it('serializeDraft 剔除瞬态字段，只保留持久字段', () => {
    const dirty = [{ ...tabs[0], result: { rowCount: 1 }, loading: true, error: 'x' } as any];
    const d = serializeDraft(7, 'tab-1', dirty);
    expect(d.v).toBe(1);
    expect(d.selectedDs).toBe(7);
    expect(d.activeKey).toBe('tab-1');
    expect(d.tabs[0]).toEqual({ key: 'tab-1', title: '查询 1', sql: 'SELECT 1', database: 'orders', maxRows: 1000 });
    expect(typeof d.savedAt).toBe('number');
  });

  it('serialize → parse 往返一致', () => {
    const back = parseDraft(JSON.stringify(serializeDraft(3, 'tab-3', tabs)));
    expect(back?.selectedDs).toBe(3);
    expect(back?.activeKey).toBe('tab-3');
    expect(back?.tabs).toEqual(tabs);
  });

  it('parseDraft 损坏数据回退 undefined', () => {
    expect(parseDraft(null)).toBeUndefined();
    expect(parseDraft('not-json')).toBeUndefined();
    expect(parseDraft(JSON.stringify({ v: 2, tabs }))).toBeUndefined(); // 版本不符
    expect(parseDraft(JSON.stringify({ v: 1, tabs: [] }))).toBeUndefined(); // 空标签组
    expect(parseDraft(JSON.stringify({ v: 1, tabs: [{ key: 'tab-1', title: '查询 1' }] }))).toBeUndefined(); // 缺 sql/maxRows
    expect(
      parseDraft(JSON.stringify({ v: 1, tabs: [{ key: 'tab-1', title: '查询 1', sql: '', maxRows: 0 }] })),
    ).toBeUndefined(); // maxRows 非法
  });

  it('修复逻辑：activeKey 缺失回退首个、selectedDs 非法置空、nextTabSeq 取最大 N+1', () => {
    const d = parseDraft(JSON.stringify({ v: 1, selectedDs: 'bad', activeKey: 'tab-99', tabs, savedAt: 1 }));
    expect(d?.activeKey).toBe('tab-1');
    expect(d?.selectedDs).toBeUndefined();
    expect(nextTabSeq(tabs)).toBe(4);
    expect(nextTabSeq([{ key: 'other' }, { key: 'tab-x' }])).toBe(1);
    expect(nextTabSeq([])).toBe(1);
  });
});
