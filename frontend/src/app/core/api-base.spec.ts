import { sanitizeApiBase } from './api-base';

describe('sanitizeApiBase', () => {
  it('keeps a valid same-origin root-relative base', () => {
    expect(sanitizeApiBase('/api')).toBe('/api');
  });

  it('keeps a valid absolute http(s) base', () => {
    expect(sanitizeApiBase('https://api.cadence.example.com')).toBe('https://api.cadence.example.com');
    expect(sanitizeApiBase('http://localhost:8080/api')).toBe('http://localhost:8080/api');
  });

  it('trims a trailing slash so `${base}/path` stays clean', () => {
    expect(sanitizeApiBase('/api/')).toBe('/api');
    expect(sanitizeApiBase('https://api.example.com/')).toBe('https://api.example.com');
  });

  it('falls back to /api for a Git-Bash-mangled Windows path (the production bug)', () => {
    expect(sanitizeApiBase('C:/Program Files/Git/api')).toBe('/api');
    expect(sanitizeApiBase('C:\\Program Files\\Git\\api')).toBe('/api');
  });

  it('falls back to /api for protocol-relative, bare-host, empty, or nullish values', () => {
    expect(sanitizeApiBase('//evil.example.com')).toBe('/api');
    expect(sanitizeApiBase('api.example.com')).toBe('/api');
    expect(sanitizeApiBase('')).toBe('/api');
    expect(sanitizeApiBase('   ')).toBe('/api');
    expect(sanitizeApiBase(null)).toBe('/api');
    expect(sanitizeApiBase(undefined)).toBe('/api');
  });

  it('falls back to /api for dangerous non-http schemes (allow-list intent)', () => {
    expect(sanitizeApiBase('file:///C:/Program Files/Git/api')).toBe('/api');
    expect(sanitizeApiBase('javascript:alert(1)')).toBe('/api');
    expect(sanitizeApiBase('data:text/html,x')).toBe('/api');
  });

  it('falls back to /api for a bare "/" that would otherwise trim to an empty base', () => {
    expect(sanitizeApiBase('/')).toBe('/api');
    expect(sanitizeApiBase('///')).toBe('/api');
  });
});
