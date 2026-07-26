import { describe, it, expect } from 'vitest';
import { humanBytes, languageForPath, nextFreeTabNo, filterRows, highlightParts, tabNeighbor } from './utils';

describe('humanBytes', () => {
  it('0 字节', () => {
    expect(humanBytes(0)).toBe('0 B');
  });

  it('字节级（小于 1KB 原样输出整数 + B）', () => {
    expect(humanBytes(512)).toBe('512 B');
  });

  it('边界：1023 仍是字节级', () => {
    expect(humanBytes(1023)).toBe('1023 B');
  });

  it('边界：1024 进入 KB', () => {
    expect(humanBytes(1024)).toBe('1.0 KB');
  });

  it('KB 保留一位小数', () => {
    expect(humanBytes(1536)).toBe('1.5 KB');
  });

  it('小数按 toFixed(1) 四舍五入', () => {
    expect(humanBytes(1234)).toBe('1.2 KB');
  });

  it('边界：1MB', () => {
    expect(humanBytes(1024 * 1024)).toBe('1.0 MB');
  });

  it('MB 保留一位小数', () => {
    expect(humanBytes(5 * 1024 * 1024)).toBe('5.0 MB');
  });

  it('边界：1GB', () => {
    expect(humanBytes(1024 * 1024 * 1024)).toBe('1.0 GB');
  });

  it('GB 保留一位小数', () => {
    expect(humanBytes(2.5 * 1024 * 1024 * 1024)).toBe('2.5 GB');
  });
});

describe('languageForPath', () => {
  it('json', () => {
    expect(languageForPath('app.json')).toBe('json');
  });

  it('yaml（.yaml）', () => {
    expect(languageForPath('config.yaml')).toBe('yaml');
  });

  it('yaml（.yml）', () => {
    expect(languageForPath('config.yml')).toBe('yaml');
  });

  it('xml', () => {
    expect(languageForPath('pom.xml')).toBe('xml');
  });

  it('sh → shell', () => {
    expect(languageForPath('deploy.sh')).toBe('shell');
  });

  it('ini', () => {
    expect(languageForPath('settings.ini')).toBe('ini');
  });

  it('conf → ini', () => {
    expect(languageForPath('nginx.conf')).toBe('ini');
  });

  it('properties → ini', () => {
    expect(languageForPath('application.properties')).toBe('ini');
  });

  it('js → javascript', () => {
    expect(languageForPath('index.js')).toBe('javascript');
  });

  it('ts → typescript', () => {
    expect(languageForPath('main.ts')).toBe('typescript');
  });

  it('扩展名大小写不敏感', () => {
    expect(languageForPath('DATA.JSON')).toBe('json');
  });

  it('未知扩展名 → plaintext', () => {
    expect(languageForPath('notes.txt')).toBe('plaintext');
  });

  it('无扩展名 → plaintext', () => {
    expect(languageForPath('README')).toBe('plaintext');
  });
});

describe('nextFreeTabNo', () => {
  it('空列表从 1 开始', () => {
    expect(nextFreeTabNo([])).toBe(1);
  });

  it('连续占用取下一个', () => {
    expect(nextFreeTabNo(['查询 1', '查询 2'])).toBe(3);
  });

  it('递补关闭标签留下的空缺', () => {
    expect(nextFreeTabNo(['查询 1', '查询 3'])).toBe(2);
  });

  it('忽略不符合命名规律的标题', () => {
    expect(nextFreeTabNo(['其他', '查询 2'])).toBe(1);
  });

  it('两位数编号正常解析', () => {
    expect(nextFreeTabNo(['查询 1', '查询 2', '查询 10'])).toBe(3);
  });
});

describe('filterRows', () => {
  const rows: (string | null)[][] = [
    ['1', 'Alice', '2026-01-01'],
    ['2', 'Bob', null],
    ['3', 'ALICE2', 'x'],
  ];

  it('空关键字原样返回（同一引用）', () => {
    expect(filterRows(rows, '   ')).toBe(rows);
  });

  it('大小写不敏感，匹配任意列', () => {
    expect(filterRows(rows, 'alice')).toEqual([rows[0], rows[2]]);
  });

  it('null 单元格不报错也不匹配', () => {
    expect(filterRows(rows, 'null')).toEqual([]);
  });

  it('无匹配返回空数组', () => {
    expect(filterRows(rows, 'zzz')).toEqual([]);
  });

  it('数字按字符串匹配', () => {
    expect(filterRows(rows, '2026')).toEqual([rows[0]]);
  });
});

describe('highlightParts', () => {
  it('空 query 返回整段不高亮', () => {
    expect(highlightParts('hello world', '')).toEqual([{ text: 'hello world', hit: false }]);
  });

  it('无匹配返回整段不高亮', () => {
    expect(highlightParts('hello', 'zzz')).toEqual([{ text: 'hello', hit: false }]);
  });

  it('单个命中（默认大小写不敏感）', () => {
    expect(highlightParts('ERROR at line', 'error')).toEqual([
      { text: 'ERROR', hit: true },
      { text: ' at line', hit: false },
    ]);
  });

  it('多个命中', () => {
    expect(highlightParts('a-b-a', 'a')).toEqual([
      { text: 'a', hit: true },
      { text: '-b-', hit: false },
      { text: 'a', hit: true },
    ]);
  });

  it('区分大小写时不命中不同大小写', () => {
    expect(highlightParts('Error error', 'error', true)).toEqual([
      { text: 'Error ', hit: false },
      { text: 'error', hit: true },
    ]);
  });

  it('query 含正则特殊字符按字面匹配', () => {
    expect(highlightParts('a.b axb', 'a.b')).toEqual([
      { text: 'a.b', hit: true },
      { text: ' axb', hit: false },
    ]);
  });
});

describe('tabNeighbor', () => {
  it('关闭激活标签：优先左邻居', () => {
    expect(tabNeighbor(['/a', '/b', '/c'], '/b', '/b')).toBe('/a');
  });

  it('关闭最左激活标签：取右邻居', () => {
    expect(tabNeighbor(['/a', '/b'], '/a', '/a')).toBe('/b');
  });

  it('关闭唯一标签：无可激活返回 undefined', () => {
    expect(tabNeighbor(['/a'], '/a', '/a')).toBeUndefined();
  });

  it('关闭非激活标签：激活保持不变', () => {
    expect(tabNeighbor(['/a', '/b', '/c'], '/a', '/c')).toBe('/c');
  });
});
